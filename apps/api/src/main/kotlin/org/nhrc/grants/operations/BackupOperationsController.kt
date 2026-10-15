package org.nhrc.grants.operations

import org.nhrc.grants.config.PlatformIdentity
import org.springframework.core.env.Environment
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.http.ResponseEntity
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException
import java.nio.file.Files
import java.nio.file.Path
import java.time.OffsetDateTime
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.Semaphore

@RestController
@RequestMapping("/api/operations/backups")
class BackupOperationsController(private val jdbc:JdbcTemplate,private val identity:PlatformIdentity,private val env:Environment) {
 private val lease=Semaphore(1)
 private fun actor()=identity.current().also { if(it.roles.none { role ->role in setOf("IT_ADMIN","SUPERADMIN") })throw ResponseStatusException(HttpStatus.FORBIDDEN,"Technical backup authority is required") }
 private fun directory():Path=Path.of(env.getProperty("NHRC_BACKUP_DIRECTORY","/app/backups")!!).toAbsolutePath().normalize()
 private fun command(vararg args:String) {
   val command=ProcessBuilder(args.toList());command.environment()["PGHOST"]=env.getProperty("POSTGRES_HOST","postgres")!!;command.environment()["PGUSER"]=env.getProperty("POSTGRES_USER","nhrc_grants")!!;command.environment()["PGPASSWORD"]=env.getProperty("POSTGRES_PASSWORD") ?: "";command.environment()["PGPORT"]=env.getProperty("POSTGRES_PORT","5432")!!
   val log=Files.createTempFile("nhrc-backup-job-",".log");val process=command.redirectErrorStream(true).redirectOutput(log.toFile()).start()
   try {if(!process.waitFor(90,TimeUnit.SECONDS)){process.destroyForcibly();throw IllegalStateException("Backup job exceeded its time limit")};if(process.exitValue()!=0)throw IllegalStateException("Database tool reported an error; inspect the technical job logs")}finally{Files.deleteIfExists(log)}
 }
 @PostMapping("/run") fun backup():Map<String,Any> {
   val actor=actor();if(!lease.tryAcquire())throw ResponseStatusException(HttpStatus.CONFLICT,"A backup or recovery test is already running")
   val id=UUID.randomUUID();val started=OffsetDateTime.now();val dir=directory();Files.createDirectories(dir);val file=dir.resolve("nhrc-$id.dump")
   jdbc.update("insert into backup_runs(id,backup_type,started_at,status,storage_location) values (?,'DATABASE',?,'RUNNING',?)",id,started,file.toString())
   try {command("pg_dump","-Fc","--file",file.toString(),env.getProperty("POSTGRES_DB","nhrc_grants")!!);command("pg_restore","--list",file.toString());jdbc.update("update backup_runs set status='SUCCESS',completed_at=now(),verification_status='ARCHIVE_VERIFIED',size_bytes=? where id=?",Files.size(file),id);jdbc.update("insert into privileged_events(actor_user_id,action,target_type,target_id,reason) values (?,'DATABASE_BACKUP','BACKUP',?,'Named technical officer requested local database backup')",actor.id,id.toString());return mapOf("id" to id,"status" to "SUCCESS")}catch(ex:Exception){jdbc.update("update backup_runs set status='FAILED',completed_at=now() where id=?",id);throw ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,ex.message)}finally{lease.release()}
 }
 @PostMapping("/{id}/verify-restore") fun restore(@PathVariable id:UUID):Map<String,Any> {
   val actor=actor();val file=backupFile(id);if(!lease.tryAcquire())throw ResponseStatusException(HttpStatus.CONFLICT,"A backup or recovery test is already running")
   val probe="nhrc_restore_"+UUID.randomUUID().toString().replace("-","");val test=UUID.randomUUID();val started=System.nanoTime();var created=false
   try {
     command("createdb",probe);created=true;command("pg_restore","--exit-on-error","--dbname",probe,file.toString())
     jdbc.update("insert into recovery_tests(id,backup_run_id,tested_at,status,recovery_minutes,notes,tested_by) values (?,?,now(),'SUCCESS',?,'Backup restored into an isolated temporary database; active grant database was not replaced',?)",test,id,((System.nanoTime()-started)/60_000_000_000).toInt(),actor.id)
     jdbc.update("update backup_runs set verification_status='RESTORE_VERIFIED' where id=?",id)
     return mapOf("id" to test,"status" to "SUCCESS")
   } catch(ex:Exception){jdbc.update("insert into recovery_tests(id,backup_run_id,tested_at,status,notes,tested_by) values (?,?,now(),'FAILED',?,?)",test,id,ex.message,actor.id);throw ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,"Isolated recovery test failed")}
   finally {if(created && probe.matches(Regex("nhrc_restore_[a-f0-9]{32}")))runCatching { command("dropdb",probe) };lease.release()}
 }
 private fun backupFile(id:UUID):Path {
   val name=jdbc.queryForObject("select storage_location from backup_runs where id=? and status='SUCCESS'",String::class.java,id) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND,"Backup unavailable")
   val file=Path.of(name).toAbsolutePath().normalize();if(!file.startsWith(directory()) || !Files.isRegularFile(file))throw ResponseStatusException(HttpStatus.NOT_FOUND,"This backup is retained outside the managed download directory");return file
 }
 @GetMapping("/{id}/download") fun download(@PathVariable id:UUID):ResponseEntity<ByteArray> {actor();val file=backupFile(id);return ResponseEntity.ok().header("Content-Disposition","attachment; filename=\"${file.fileName}\"").header("Content-Type","application/octet-stream").body(Files.readAllBytes(file))}
}
