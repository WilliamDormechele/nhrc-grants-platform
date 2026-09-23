param(
    [switch]$SkipWebBuild
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

function Assert-Native([string]$Step) {
    if ($LASTEXITCODE -ne 0) {
        throw "$Step failed with exit code $LASTEXITCODE"
    }
}

function Invoke-Checked([string]$Uri) {
    try {
        return Invoke-RestMethod -Uri $Uri -TimeoutSec 15
    } catch {
        throw "Request failed: $Uri. $($_.Exception.Message)"
    }
}

Write-Host "=== NHRC Grants safe UAT update ===" -ForegroundColor Cyan

if (-not (Test-Path ".git")) {
    throw "Run this script from the nhrc-grants-platform repository root."
}

git diff --quiet
Assert-Native "Tracked working-tree check"
git diff --cached --quiet
Assert-Native "Staged working-tree check"

Write-Host "[1/9] Updating develop..." -ForegroundColor Yellow
git checkout develop
Assert-Native "git checkout develop"
git pull --ff-only origin develop
Assert-Native "git pull"

$duplicates = Get-ChildItem "apps/api/src/main/resources/db/migration/V*__*.sql" |
    ForEach-Object {
        if ($_.Name -match '^V(\d+)__') {
            [PSCustomObject]@{ Version = [int]$Matches[1]; Name = $_.Name }
        }
    } | Group-Object Version | Where-Object Count -gt 1
if ($duplicates) {
    $detail = ($duplicates | ForEach-Object { "$($_.Name): $((($_.Group).Name) -join ', ')" }) -join "; "
    throw "Duplicate Flyway migration versions detected before build: $detail"
}

Write-Host "[2/9] Ensuring database services are available..." -ForegroundColor Yellow
docker compose up -d postgres redis
Assert-Native "Starting PostgreSQL/Redis"

$backupDir = Join-Path (Get-Location) "backups"
New-Item -ItemType Directory -Force -Path $backupDir | Out-Null
if (Test-Path ".\pre_v011_repair.dump") {
    Move-Item ".\pre_v011_repair.dump" (Join-Path $backupDir "pre_v011_repair_previous.dump") -Force
}
$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$containerBackup = "/tmp/nhrc-grants-$stamp.dump"
$hostBackup = Join-Path $backupDir "nhrc-grants-$stamp.dump"

Write-Host "[3/9] Creating PostgreSQL backup..." -ForegroundColor Yellow
docker exec nhrc-grants-postgres pg_dump -U nhrc_grants -d nhrc_grants -Fc -f $containerBackup
Assert-Native "Database backup"
docker cp "nhrc-grants-postgres:$containerBackup" $hostBackup
Assert-Native "Copying database backup"
if ((Get-Item $hostBackup).Length -le 0) { throw "Database backup is empty." }
Write-Host "Backup: $hostBackup" -ForegroundColor Green

Write-Host "[4/9] Reconciling the known UAT V011 history only if required..." -ForegroundColor Yellow
$v11 = docker exec nhrc-grants-postgres psql -U nhrc_grants -d nhrc_grants -At -F "|" -c "SELECT description,checksum FROM flyway_schema_history WHERE version='011' AND success=TRUE LIMIT 1;"
Assert-Native "Reading Flyway V011 history"
$v11 = ($v11 | Out-String).Trim()
if ($v11 -eq "grant intake profiles and review controls|-1618236750" -or $v11 -eq "grant intake profiles and review controls|1874565598") {
    docker exec nhrc-grants-postgres psql -U nhrc_grants -d nhrc_grants -v ON_ERROR_STOP=1 -c "UPDATE flyway_schema_history SET description='nhrc pregrant operations', script='V011__nhrc_pregrant_operations.sql', checksum=1874565598 WHERE version='011' AND success=TRUE AND description='grant intake profiles and review controls';"
    Assert-Native "Reconciling known V011 history"
    Write-Host "Known legacy V011 history reconciled." -ForegroundColor Green
} elseif ($v11 -eq "nhrc pregrant operations|1874565598" -or [string]::IsNullOrWhiteSpace($v11)) {
    Write-Host "V011 history already compatible or not yet installed." -ForegroundColor Green
} else {
    throw "Unexpected V011 history '$v11'. No automatic history change was made. Backup is at $hostBackup"
}

$v12 = docker exec nhrc-grants-postgres psql -U nhrc_grants -d nhrc_grants -At -F "|" -c "SELECT description,checksum FROM flyway_schema_history WHERE version='012' AND success=TRUE LIMIT 1;"
Assert-Native "Reading Flyway V012 history"
$v12 = ($v12 | Out-String).Trim()
if ($v12 -eq "reconcile nhrc pregrant schema|905675409") {
    docker exec nhrc-grants-postgres psql -U nhrc_grants -d nhrc_grants -v ON_ERROR_STOP=1 -c "DELETE FROM flyway_schema_history WHERE version='012' AND success=TRUE AND description='reconcile nhrc pregrant schema' AND checksum=905675409;"
    Assert-Native "Removing obsolete UAT V012 history"
    Write-Host "Obsolete V012 history removed; canonical V012 will run normally." -ForegroundColor Green
} elseif ([string]::IsNullOrWhiteSpace($v12) -or $v12 -eq "converge nhrc pregrant schema|182670125") {
    Write-Host "V012 history is ready." -ForegroundColor Green
} else {
    throw "Unexpected V012 history '$v12'. No automatic history change was made. Backup is at $hostBackup"
}

Write-Host "[5/9] Building API and running all tests..." -ForegroundColor Yellow
docker compose build api --no-cache
Assert-Native "API build/tests"

if (-not $SkipWebBuild) {
    Write-Host "[6/9] Building web application..." -ForegroundColor Yellow
    docker compose build web --no-cache
    Assert-Native "Web build"
} else {
    Write-Host "[6/9] Web build skipped by request." -ForegroundColor DarkYellow
}

Write-Host "[7/9] Starting application..." -ForegroundColor Yellow
docker compose up -d --force-recreate api web
Assert-Native "Application startup"

Write-Host "[8/9] Waiting for API health..." -ForegroundColor Yellow
$healthy = $false
for ($i=1; $i -le 30; $i++) {
    Start-Sleep -Seconds 2
    try {
        $health = Invoke-RestMethod -Uri "http://localhost:8080/actuator/health" -TimeoutSec 4
        if ($health.status -eq "UP") { $healthy = $true; break }
    } catch {}
}
if (-not $healthy) {
    Write-Host "API did not become healthy. Recent logs:" -ForegroundColor Red
    docker compose logs api --tail 180
    throw "API health check failed."
}

Write-Host "[9/9] Running smoke tests..." -ForegroundColor Yellow
$checks = [ordered]@{
    Health = "http://localhost:8080/actuator/health"
    Dashboard = "http://localhost:8080/api/dashboard/summary"
    Opportunities = "http://localhost:8080/api/opportunities"
    Applications = "http://localhost:8080/api/applications"
    Awards = "http://localhost:8080/api/awards"
    Finance = "http://localhost:8080/api/finance/summary"
    PreGrantAnalytics = "http://localhost:8080/api/pregrant/analytics"
    Requirements = "http://localhost:8080/api/pregrant/requirements"
    QualityChecks = "http://localhost:8080/api/pregrant/quality-checks"
    Approvals = "http://localhost:8080/api/approvals"
}
foreach ($name in $checks.Keys) {
    $null = Invoke-Checked $checks[$name]
    Write-Host "PASS $name" -ForegroundColor Green
}

$versions = docker exec nhrc-grants-postgres psql -U nhrc_grants -d nhrc_grants -At -c "SELECT version || ':' || success FROM flyway_schema_history ORDER BY installed_rank;"
Assert-Native "Reading Flyway history"

Write-Host ""
Write-Host "=== NHRC Grants update completed successfully ===" -ForegroundColor Green
Write-Host "Flyway: $((($versions | Out-String).Trim()) -replace [Environment]::NewLine, ', ')"
Write-Host "Web: http://localhost:3000"
Write-Host "API health: http://localhost:8080/actuator/health"
Write-Host "Backup retained at: $hostBackup"
