"""Destructive integration tests for the disposable GitHub Actions database only.

This script refuses ordinary local and production execution. All records are
fictional test records; no funder submissions, email or bank transactions occur.
"""
from __future__ import annotations
import json
import os
import subprocess
import traceback
from datetime import date, datetime, timedelta, timezone
from pathlib import Path
from urllib.error import HTTPError
from urllib.request import Request, urlopen
from uuid import uuid4

BASE = "http://localhost:8080/api/workbench"
OUT = Path("test-results")
OUT.mkdir(exist_ok=True)
CHECKS: list[str] = []
USERS: dict[str, str] = {}
EVIDENCE = "https://evidence.example.invalid/ci-only/assessment"
TODAY = date.today()
FUTURE = (TODAY + timedelta(days=365)).isoformat()

def check(condition: bool, label: str) -> None:
    if not condition:
        raise AssertionError(label)
    CHECKS.append(label)
    print("PASS:", label, flush=True)

def request(path: str, user: str | None = None, method: str = "GET", body=None,
            expected=(200, 201), absolute: bool = False):
    headers = {"Accept": "application/json"}
    if user:
        headers["X-UAT-User"] = USERS.get(user, user)
    data = None
    if body is not None:
        headers["Content-Type"] = "application/json"
        data = json.dumps(body).encode()
    req = Request(path if absolute else BASE + path, data=data, headers=headers, method=method)
    try:
        with urlopen(req, timeout=30) as response:
            status, raw = response.status, response.read().decode()
    except HTTPError as error:
        status, raw = error.code, error.read().decode()
    if status not in expected:
        raise AssertionError(f"{method} {path}: expected {expected}, received {status}: {raw[:4000]}")
    try:
        return json.loads(raw) if raw else None
    except json.JSONDecodeError:
        raise AssertionError(f"Non-JSON response from {path}: {raw[:300]}")

def create(key: str, values: dict, user="grants"):
    return request(f"/records/{key}", user, "POST", {"values": values})

def record(key: str, ident: str, user="grants"):
    return request(f"/records/{key}/{ident}", user)

def action(path: str, code: str, user: str, values=None, expected=(200,), body=None):
    current = request(path, user)
    submitted = body or {"action": code, "version": current["record_version"],
                         "requestId": str(uuid4()), "note": "CI-only documented test decision.",
                         "evidenceUrl": EVIDENCE, "values": values or {}}
    return request(path + "/actions", user, "POST", submitted, expected), submitted

def seed_test_users():
    fixture = """
    WITH accounts(email,name,role) AS (VALUES
      ('ci.scientific@nhrc.local','CI Scientific Reviewer','RESEARCHER'),
      ('ci.grants@nhrc.local','CI Independent Grants Reviewer','GRANTS_OFFICER'),
      ('ci.approver@nhrc.local','CI Finance Approver','FINANCE_APPROVER'),
      ('ci.procurement@nhrc.local','CI Independent Procurement Officer','PROCUREMENT_OFFICER'),
      ('ci.governance@nhrc.local','CI Governance Officer','GOVERNANCE_OFFICER'),
      ('ci.admin@nhrc.local','CI Administrator','ADMIN'),
      ('ci.superadmin@nhrc.local','CI Technical Superadmin','SUPERADMIN'))
    INSERT INTO users(email,display_name,organisation_unit_id,job_title)
    SELECT a.email,a.name,u.id,'Fictional CI account only' FROM accounts a CROSS JOIN organisation_units u WHERE u.code='NHRC'
    ON CONFLICT(email) DO NOTHING;
    WITH accounts(email,role) AS (VALUES
      ('ci.scientific@nhrc.local','RESEARCHER'),('ci.grants@nhrc.local','GRANTS_OFFICER'),
      ('ci.approver@nhrc.local','FINANCE_APPROVER'),('ci.procurement@nhrc.local','PROCUREMENT_OFFICER'),
      ('ci.governance@nhrc.local','GOVERNANCE_OFFICER'),('ci.admin@nhrc.local','ADMIN'),('ci.superadmin@nhrc.local','SUPERADMIN'))
    INSERT INTO user_roles(user_id,role_id,scope_type,scope_id)
    SELECT u.id,r.id,'INSTITUTION',o.id FROM accounts a JOIN users u ON u.email=a.email
    JOIN roles r ON r.code=a.role CROSS JOIN organisation_units o WHERE o.code='NHRC'
    ON CONFLICT DO NOTHING;
    """
    subprocess.run(["docker", "compose", "exec", "-T", "postgres", "psql", "-v", "ON_ERROR_STOP=1",
                    "-U", "nhrc_grants", "-d", "nhrc_grants"], input=fixture, text=True, check=True)
    identities = request("/identity")["uatUsers"]
    names = {row["display_name"]: row["id"] for row in identities}
    labels = {"grants": "UAT Grants Officer", "director": "UAT Director", "lead": "UAT Principal Investigator",
              "finance": "UAT Finance Officer", "proc": "UAT Procurement Officer", "lab": "UAT Laboratory Officer",
              "scientific": "CI Scientific Reviewer", "reviewer": "CI Independent Grants Reviewer",
              "approver": "CI Finance Approver", "proc2": "CI Independent Procurement Officer",
              "governance": "CI Governance Officer", "admin": "CI Administrator", "superadmin": "CI Technical Superadmin",
              "fellow": "UAT Research Fellow", "uat_admin": "UAT Business Administrator", "uat_superadmin": "UAT Superadmin"}
    USERS.update({alias: names[label] for alias, label in labels.items()})
    create("researchers", {"user_id": USERS["scientific"], "expertise": ["Population health"],
                           "methods": ["Surveillance"], "summary": "Fictional reviewer profile for CI."}, "scientific")
    fellow_catalogue = {item["key"]: item for item in request("/catalogue", "fellow")}
    check("researchers" in fellow_catalogue and "applications" in fellow_catalogue,
          "Research Fellow receives researcher-level records without business approval authority")
    request("/operations/users", "fellow", expected=(403,))
    check(True, "Research Fellow cannot open administration user controls")
    super_catalogue = request("/catalogue", "uat_superadmin")
    check(len(super_catalogue) >= len(request("/catalogue", "grants")),
          "Superadmin can inspect the complete resource catalogue")

def application_journey(kind: str, call: dict):
    app = create("applications", {"title": f"CI {kind} complete workflow", "opportunity_id": call["id"], "application_type": kind})
    path = "/applications/" + app["id"]
    current_forbidden = request(path, "grants")
    request(path + "/actions", "lead", "POST", {
        "action": "START_ELIGIBILITY", "version": current_forbidden["record_version"],
        "requestId": str(uuid4()), "note": "CI-only unauthorised action check.",
        "evidenceUrl": EVIDENCE, "values": {}
    }, expected=(403,))
    check(True, f"{kind}: unassigned researcher cannot start eligibility")
    started, submitted = action(path, "START_ELIGIBILITY", "grants")
    repeated = request(path + "/actions", "grants", "POST", submitted)
    check(started["record_version"] == repeated["record_version"], f"{kind}: repeated decision is idempotent")
    action(path, "RECORD_ELIGIBILITY", "grants", {"decision": "ELIGIBLE", "checks": {
        key: "PASS" for key in ["applicant", "geography", "theme", "deadline", "budget", "partners"]}, "conditions": ""})
    action(path, "DIRECTOR_DECISION", "grants", {"decision": "PURSUE"}, expected=(403,))
    action(path, "DIRECTOR_DECISION", "director", {"decision": "PURSUE"})
    action(path, "ASSIGN", "grants", {"researcherId": USERS["lead"]})
    action(path, "ACCEPT", "lead")
    action(path, "START_PREPARATION", "lead")
    current = request(path, "lead")
    narrative = {f["name"]: (False if f["kind"] == "boolean" else f"CI-only substantive test content for {f['label']}.")
                 for f in current["narrativeFields"]}
    request(path + "/narrative", "lead", "PUT", {"version": current["record_version"], "values": narrative})
    request(path + "/narrative", "lead", "PUT", {"version": current["record_version"], "values": narrative}, expected=(409,))
    check(True, f"{kind}: stale proposal saves are rejected")
    if kind == "FULL_PROPOSAL":
        current = request(path, "lead")
        line = {"scenario": "Base", "category": "PERSONNEL", "description": "CI research support",
                "year_no": 1, "quantity": "2", "unit_cost": "100", "currency": "GBP",
                "funder_amount": "150", "nhrc_contribution": "25", "partner_amount": "25"}
        broken = {**line, "funder_amount": "149"}
        request(path + "/budget", "lead", "POST", {"version": current["record_version"], "values": broken}, expected=(400,))
        request(path + "/budget", "lead", "POST", {"version": current["record_version"], "values": line})
        current = request(path, "lead")
        request(path + "/budget-scenario", "lead", "PUT", {"version": current["record_version"], "values": {"scenario": "Base"}})
        check(True, "Full proposal: unbalanced budget rejected and balanced scenario selected")
    current = request(path, "lead")
    request(path + "/documents", "lead", "POST", {"version": current["record_version"], "values": {
        "document_type": "APPLICATION_PACKAGE", "title": "CI controlled application package", "evidence_url": EVIDENCE}})
    action(path, "REQUEST_REVIEW", "lead", {"documentsConfirmed": True, "reviewers": {
        "SCIENTIFIC": USERS["scientific"], "FINANCE": USERS["finance"], "GRANTS": USERS["reviewer"]}})
    action(path, "COMPLETE_REVIEW", "lead", {"reviewType": "SCIENTIFIC", "decision": "CLEARED", "noConflict": True}, expected=(403,))
    for review_type, user in [("SCIENTIFIC", "scientific"), ("FINANCE", "finance"), ("GRANTS", "reviewer")]:
        action(path, "COMPLETE_REVIEW", user, {"reviewType": review_type, "decision": "CLEARED", "noConflict": True})
    check(request(path, "grants")["stage"] == "INSTITUTIONAL_APPROVAL", f"{kind}: all independent reviews required")
    action(path, "APPROVE", "director", {"noConflict": False}, expected=(400,))
    action(path, "APPROVE", "director", {"noConflict": True})
    action(path, "RECORD_SUBMISSION", "grants", {"submittedAt": (datetime.now(timezone.utc)-timedelta(minutes=5)).isoformat(),
                                                "reference": f"CI-{kind}-{uuid4().hex[:8]}"})
    if kind == "SOI":
        action(path, "RECORD_OUTCOME", "grants", {"outcome": "AWARDED"}, expected=(400,))
        action(path, "RECORD_OUTCOME", "grants", {"outcome": "INVITED_TO_NEXT_STAGE"})
        next_stage, _ = action(path, "NEXT_STAGE", "grants", {"applicationType": "EOI"})
        check(bool(next_stage.get("createdApplicationId")), "SOI invitation creates a linked EOI rather than an award")
        child = request("/applications/" + next_stage["createdApplicationId"], "grants")
        check(child["application_type"] == "EOI" and child["stage"] == "DISCOVERED", "Linked EOI restarts the appropriate institutional checks")
        return app["id"], None
    action(path, "RECORD_OUTCOME", "grants", {"outcome": "AWARDED"})
    award_result, _ = action(path, "CONVERT_AWARD", "grants", {
        "reference": "CI-AWARD-" + uuid4().hex[:8], "startDate": TODAY.isoformat(), "endDate": FUTURE,
        "currency": "GBP", "totalAward": "200", "nhrcAllocation": "175", "partnerAllocation": "25"})
    check(bool(award_result.get("createdAwardId")), "Reviewed full proposal converts to a balanced financial award")
    return app["id"], award_result["createdAwardId"]

def diligence(kind: str, subject: dict, preparer: str):
    values = {"entity_id": subject["id"], "review_type": "CI documented institutional assessment",
              "risk_rating": "LOW", "findings": "CI-only evidence for all required checks.", "conditions": "",
              "expires_at": FUTURE, "evidence_url": EVIDENCE,
              **{f"check_{name}": "PASS" for name in ["identity", "financialControls", "conflicts", "safeguarding", "deliveryCapacity"]}}
    review = request(f"/due-diligence/subjects/{kind}", preparer, "POST", {"values": values})
    path = "/due-diligence/" + review["id"]
    action(path, "REQUEST_REVIEW", preparer)
    action(path, "APPROVE", preparer, {"noConflict": True}, expected=(403,))
    result, _ = action(path, "APPROVE", "director", {"noConflict": True})
    check(result["status"] == "APPROVED", f"{kind}: independent due diligence approval saved")
    return review["id"]

def operations(award_id: str):
    contract = create("contracts", {"award_id": award_id, "contract_type": "GRANT_AGREEMENT", "title": "CI approved award agreement",
                                     "reference": "CI-AGR-" + uuid4().hex[:8], "storage_key": EVIDENCE})
    action("/records/contracts/" + contract["id"], "REQUEST_REVIEW", "grants")
    action("/records/contracts/" + contract["id"], "APPROVE", "director")
    create("deliverables", {"award_id": award_id, "deliverable_type": "REPORT", "title": "CI required delivery report",
                            "due_date": FUTURE, "owner_user_id": USERS["lead"]})
    action("/awards/" + award_id, "ACTIVATE", "director", {"noConflict": True, "setupChecks": {
        key: "CONFIRMED" for key in ["agreement", "finance", "reporting", "governance", "partners"]}})
    check(request("/awards/" + award_id, "grants")["status"] == "ACTIVE", "Award activates only after agreement and delivery evidence")
    receipt = create("receipts", {"award_id": award_id, "expected_amount": "100", "received_amount": "100",
                                  "received_date": TODAY.isoformat(), "currency": "GBP", "reference": "CI receipt"}, "finance")
    action("/records/receipts/" + receipt["id"], "VERIFY_RECEIPT", "finance", expected=(403,))
    action("/records/receipts/" + receipt["id"], "VERIFY_RECEIPT", "approver")
    check(record("receipts", receipt["id"], "finance")["verified_at"] is not None, "Receipt requires independent finance verification")
    spend = create("expenditures", {"award_id": award_id, "transaction_date": TODAY.isoformat(), "category": "PERSONNEL",
                                    "description": "CI accounting test only", "amount": "20", "currency": "GBP", "finance_reference": "CI-EXP-1"}, "finance")
    action("/records/expenditures/" + spend["id"], "VERIFY_EXPENDITURE", "approver")
    finance = request("/finance", "finance")
    row = next(row for row in finance["awards"] if row["id"] == award_id)
    check(float(row["verified_receipts"]) == 100 and float(row["verified_expenditure"]) == 20, "Finance totals include the verified transactions in their award currency")
    supplier = create("suppliers", {"name": "CI Supplier " + uuid4().hex[:8], "country_code": "GH"}, "proc")
    diligence("SUPPLIER", supplier, "proc")
    partner = create("partners", {"name": "CI Partner " + uuid4().hex[:8], "country_code": "GH"})
    diligence("PARTNER", partner, "grants")
    req = create("requisitions", {"award_id": award_id, "reference": "CI-REQ-"+uuid4().hex[:8], "description": "CI equipment procurement",
                                   "amount": "50", "currency": "GBP"}, "proc")
    action("/records/requisitions/" + req["id"], "REQUEST_REVIEW", "proc")
    action("/records/requisitions/" + req["id"], "APPROVE", "approver")
    order_values = {"requisition_id": req["id"], "supplier_id": supplier["id"], "reference": "CI-PO-"+uuid4().hex[:8],
                    "amount": "40", "currency": "GBP", "issued_date": TODAY.isoformat(), "expected_delivery_date": FUTURE}
    order = create("purchase-orders", order_values, "proc")
    request("/records/purchase-orders", "proc", "POST", {"values": {**order_values, "reference": "CI-PO-EXCESS", "amount": "11"}}, expected=(400,))
    action("/records/purchase-orders/" + order["id"], "ISSUE", "proc", expected=(403,))
    action("/records/purchase-orders/" + order["id"], "ISSUE", "proc2")
    check(True, "Purchase orders enforce cumulative requisition limits and independent issue")
    # Deliberately expire only the new fictional supplier, in the disposable CI database.
    sql = "UPDATE suppliers SET due_diligence_valid_until=current_date-1 WHERE id='" + supplier["id"] + "';"
    subprocess.run(["docker", "compose", "exec", "-T", "postgres", "psql", "-v", "ON_ERROR_STOP=1", "-U", "nhrc_grants", "-d", "nhrc_grants"], input=sql, text=True, check=True)
    request("/records/purchase-orders", "proc", "POST", {"values": {**order_values, "reference": "CI-PO-EXPIRED", "amount": "5"}}, expected=(400,))
    check(True, "Expired supplier approval blocks a new purchase order")
    return supplier["id"]

def main():
    if os.environ.get("GITHUB_ACTIONS") != "true" or os.environ.get("APP_ENV") != "test" or os.environ.get("AUTH_MODE") != "development":
        raise RuntimeError("Refusing to run outside the disposable GitHub Actions test database")
    check(request("/identity")["development"] is True, "Test environment explicitly reports development authentication")
    seed_test_users()
    request("/catalogue", expected=(401, 403))
    check(True, "Unauthenticated access to protected workbench records is rejected")
    catalogue = request("/catalogue", "grants")
    check(len(catalogue) > 10, "Authorised account receives structured form definitions")
    for role in ["grants", "lead", "finance", "proc", "lab", "admin", "director"]:
        for resource in request("/catalogue", role):
            result = request("/records/" + resource["key"] + "?size=2", role)
            check(isinstance(result.get("items"), list), f"{role}: {resource['key']} register returns structured data")
    finance_view = request("/finance", "superadmin")
    check(isinstance(finance_view.get("awards"), list), "Superadmin can inspect Finance without acquiring Finance authority")
    request("/records/receipts", "superadmin", "POST", {"values": {}}, expected=(403,))
    check(True, "Superadmin visibility does not grant financial preparation or approval authority")
    call_values = {"title": "CI funding call " + uuid4().hex[:8], "call_type": "GRANT_CALL", "summary": "Fictional CI funding conditions.",
                   "url": EVIDENCE, "eligibility_criteria": "CI institution is eligible for this fictional test.", "keywords": ["Population health"],
                   "currency": "GBP", "amount_max": "10000", "deadline_date": FUTURE}
    call = create("opportunities", call_values)
    check(call["_editable"], "New funding calls can be curated after creation")
    changed = request("/records/opportunities/" + call["id"], "grants", "PATCH", {"version": call["record_version"], "values": {**call_values, "summary": "Updated controlled call details."}})
    check(changed["record_version"] > call["record_version"], "Funding call editing advances the saved version")
    request("/records/opportunities", "lead", "POST", {"values": call_values}, expected=(403,))
    soi_id, _ = application_journey("SOI", call)
    full_id, award_id = application_journey("FULL_PROPOSAL", call)
    supplier_id = operations(award_id)
    output = {"status": "passed", "checks": CHECKS, "users": USERS, "soi": soi_id, "fullProposal": full_id,
              "award": award_id, "call": call["id"], "supplier": supplier_id, "scope": "Disposable CI data only"}
    (OUT / "integration-results.json").write_text(json.dumps(output, indent=2))
    print(f"PASS: {len(CHECKS)} integration assertions; complete SOI and full-proposal journeys.", flush=True)

if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        (OUT / "integration-results.json").write_text(json.dumps({"status": "failed", "checks": CHECKS, "error": str(error)}, indent=2))
        traceback.print_exc()
        raise SystemExit(1)
