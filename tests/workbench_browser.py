"""Connected browser tests. Run only against the disposable CI stack."""
from __future__ import annotations
import json
import os
import re
from pathlib import Path
from uuid import uuid4
from playwright.sync_api import sync_playwright, expect
from workbench_integration import request, USERS

OUT = Path("test-results")
if os.environ.get("GITHUB_ACTIONS") != "true" or os.environ.get("APP_ENV") != "test":
    raise RuntimeError("Browser writes are restricted to the disposable CI stack")
evidence = json.loads((OUT / "integration-results.json").read_text())
if evidence.get("status") != "passed":
    raise RuntimeError("Integration validation must pass before browser testing")
USERS.update(evidence["users"])
checks: list[str] = []
errors: list[str] = []
server_errors: list[str] = []
unexpected_dialogs: list[str] = []

def passed(label):
    checks.append(label)
    print("PASS:", label, flush=True)

def settle(page):
    page.wait_for_load_state("networkidle")
    expect(page.get_by_text("Loading grant information...", exact=True)).to_have_count(0, timeout=15000)

def navigate(page, name):
    button = page.locator("nav .navbtn").filter(has_text=re.compile("^" + re.escape(name) + "$"))
    if not button.is_visible():
        button.locator("xpath=ancestor::div[contains(@class,'navsection')]").locator(".navtoggle").click()
    button.click()
    expect(button).to_have_attribute("aria-current", "page")
    settle(page)

def choose(page, alias):
    page.get_by_label("UAT account", exact=True).select_option(USERS[alias])
    settle(page)
    expect(page.locator(".landinghero")).to_be_visible()

def fill(dialog, fields, values):
    by_name = {f["name"]: f for f in fields}
    for name, value in values.items():
        f = by_name[name]
        label = f["label"] + (" *" if f["required"] else "")
        locator = dialog.get_by_label(label, exact=True)
        if f["kind"] in ["select", "lookup", "boolean"]:
            locator.select_option(str(value).lower() if isinstance(value, bool) else str(value))
        else:
            locator.fill(str(value))

with sync_playwright() as p:
    browser = p.chromium.launch()
    context = browser.new_context(viewport={"width": 1536, "height": 1080}, locale="en-GB")
    context.tracing.start(screenshots=True, snapshots=True, sources=False)
    page = context.new_page()
    page.on("pageerror", lambda error: errors.append(str(error)))
    page.on("response", lambda response: server_errors.append(f"{response.status} {response.url}") if response.status >= 500 and "localhost" in response.url else None)
    def on_dialog(dialog):
        unexpected_dialogs.append(dialog.message)
        dialog.dismiss()
    page.on("dialog", on_dialog)
    try:
        page.goto("http://localhost:3000", wait_until="networkidle")
        expect(page.get_by_role("heading", name="Select a test account", exact=True)).to_be_visible()
        expect(page.locator(".brandmark img")).to_be_visible()
        assert page.locator(".brandmark img").evaluate("image => image.complete && image.naturalWidth > 0")
        choose(page, "grants")
        passed("Account selection and official institutional logo load correctly")
        grants_names = [name.strip() for name in page.locator("nav .navbtn").all_text_contents()]
        assert "Opportunity Intelligence" in grants_names and "Finance Dashboard" not in grants_names
        passed("Grants Officer navigation is role-specific")
        page.screenshot(path=str(OUT / "01-home-desktop.png"), full_page=True)
        names = page.locator("nav .navbtn").all_text_contents()
        for name in names:
            navigate(page, name.strip())
            expect(page.locator("main h1").first).to_be_visible()
            assert "client-side exception" not in page.locator("main").inner_text().lower()
        passed(f"All {len(names)} approved navigation entries render without a browser exception")
        catalogue = {item["key"]: item for item in request("/catalogue", "grants")}
        navigate(page, "Opportunity Intelligence")
        expect(page.get_by_text("Live funding-source APIs", exact=True)).to_be_visible()
        expect(page.get_by_text("Grants.gov", exact=True)).to_be_visible()
        expect(page.get_by_role("button", name="Search internet funding calls", exact=True)).to_be_visible()
        passed("Opportunity Intelligence exposes the live internet funding-source integrations")
        page.get_by_role("button", name="Add funding call", exact=True).click()
        dialog = page.get_by_role("dialog")
        title = "CI browser funding call " + uuid4().hex[:8]
        fill(dialog, catalogue["opportunities"]["fields"], {
            "title": title, "call_type": "SOI", "summary": "Browser-saved fictional funding call.",
            "eligibility_criteria": "Fictional CI eligibility conditions.", "keywords": "Population health, Surveillance"})
        dialog.get_by_role("button", name="Save record", exact=True).click()
        settle(page)
        # A saved record opens its real details, not a simulated success panel.
        expect(page.get_by_role("dialog").get_by_role("heading", name=title, exact=True)).to_be_visible()
        page.get_by_role("dialog").get_by_role("button", name="Edit record", exact=True).click()
        edit = page.get_by_role("dialog")
        fill(edit, catalogue["opportunities"]["fields"], {"summary": "Updated through the connected browser form."})
        edit.get_by_role("button", name="Save record", exact=True).click()
        settle(page)
        expect(page.get_by_role("dialog").get_by_text("Updated through the connected browser form.", exact=True)).to_be_visible()
        page.get_by_role("dialog").get_by_role("button", name="Close dialog", exact=True).click()
        calls = request("/records/opportunities?q=" + title.replace(" ", "%20"), "grants")["items"]
        call = next(row for row in calls if row["title"] == title)
        assert call["summary"] == "Updated through the connected browser form."
        passed("Funding call creation and editing persist through the validated service")
        page.screenshot(path=str(OUT / "02-discovery-desktop.png"), full_page=True)
        navigate(page, "Applications")
        page.get_by_role("button", name="Start application", exact=True).click()
        dialog = page.get_by_role("dialog")
        app_title = "CI browser SOI " + uuid4().hex[:8]
        fill(dialog, catalogue["applications"]["fields"], {"title": app_title, "opportunity_id": call["id"], "application_type": "SOI"})
        dialog.get_by_role("button", name="Save record", exact=True).click()
        settle(page)
        expect(page.get_by_role("heading", name=app_title, exact=True)).to_be_visible()
        assert not unexpected_dialogs, "A successful save must not trigger a discard-changes confirmation: " + str(unexpected_dialogs)
        start = next(action for action in request("/applications/" + request("/records/applications?q=" + app_title.replace(" ", "%20"), "grants")["items"][0]["id"], "grants")["_actions"] if action["code"] == "START_ELIGIBILITY")
        page.get_by_role("button", name=start["label"], exact=True).click()
        decision = page.get_by_role("dialog")
        decision.get_by_label("Decision note and rationale *", exact=True).fill("CI browser decision evidence.")
        decision.get_by_role("button", name=start["label"], exact=True).click()
        settle(page)
        app = request("/records/applications?q=" + app_title.replace(" ", "%20"), "grants")["items"][0]
        assert app["stage"] == "ELIGIBILITY_REVIEW"
        passed("SOI creation and its first controlled decision work through the interface")
        page.screenshot(path=str(OUT / "03-soi-workspace-desktop.png"), full_page=True)
        navigate(page, "Due Diligence")
        expect(page.get_by_role("heading", name="Partner and supplier due diligence", exact=True)).to_be_visible()
        expect(page.get_by_role("button", name="Assess partner", exact=True)).to_be_visible()
        passed("Due diligence is connected from institutional navigation")
        choose(page, "finance")
        finance_names = [name.strip() for name in page.locator("nav .navbtn").all_text_contents()]
        assert "Finance Dashboard" in finance_names and "Opportunity Intelligence" not in finance_names
        passed("Finance navigation differs from Grants navigation")
        navigate(page, "Finance Dashboard")
        expect(page.get_by_label("Award currency", exact=True)).to_be_visible()
        page.screenshot(path=str(OUT / "04-finance-desktop.png"), full_page=True)

        choose(page, "fellow")
        fellow_names = [name.strip() for name in page.locator("nav .navbtn").all_text_contents()]
        assert "Proposal Workspace" in fellow_names and "Calendar" in fellow_names
        assert "Users & Roles" not in fellow_names and "Financial Approvals" not in fellow_names
        passed("Research Fellow has researcher-level navigation without administration or approval workspaces")
        navigate(page, "Calendar")
        expect(page.get_by_role("heading", name="Calendar", exact=True)).to_be_visible()
        expect(page.get_by_role("button", name="Month", exact=True)).to_be_visible()
        expect(page.get_by_role("button", name="Week", exact=True)).to_be_visible()
        expect(page.get_by_role("button", name="Agenda", exact=True)).to_be_visible()
        page.get_by_role("button", name="Agenda", exact=True).click()
        expect(page.get_by_role("heading", name="Agenda", exact=True)).to_be_visible()
        passed("Calendar provides senior month, week and agenda views")

        choose(page, "uat_admin")
        admin_names = [name.strip() for name in page.locator("nav .navbtn").all_text_contents()]
        assert "Users & Roles" in admin_names and "Organisation Structure" in admin_names
        assert "Superadmin Console" not in admin_names
        passed("Business Administrator receives administration workspaces without Superadmin controls")

        choose(page, "uat_superadmin")
        super_names = [name.strip() for name in page.locator("nav .navbtn").all_text_contents()]
        assert len(super_names) > len(grants_names) and "Superadmin Console" in super_names and "Finance Dashboard" in super_names
        passed("Superadmin can see the complete feature navigation")
        choose(page, "scientific")
        navigate(page, "Researcher Profiles")
        page.get_by_label("Search this register", exact=True).fill("CI Scientific Reviewer")
        settle(page)
        page.get_by_role("button", name="Open profile", exact=True).click()
        page.get_by_role("dialog").get_by_role("button", name="Edit record", exact=True).click()
        profile_fields = next(item for item in request("/catalogue", "scientific") if item["key"] == "researchers")["fields"]
        fill(page.get_by_role("dialog"), profile_fields, {"summary": "Updated own researcher profile through the browser."})
        page.get_by_role("dialog").get_by_role("button", name="Save record", exact=True).click()
        settle(page)
        expect(page.get_by_role("dialog").get_by_text("Updated own researcher profile through the browser.", exact=True)).to_be_visible()
        page.get_by_role("dialog").get_by_role("button", name="Close dialog", exact=True).click()
        passed("Researchers can maintain their own named profile without acquiring approval powers")
        choose(page, "lab")
        lab_names = [name.strip() for name in page.locator("nav .navbtn").all_text_contents()]
        assert "Maintenance & Calibration" in lab_names and "Users & Roles" not in lab_names
        passed("Laboratory navigation is role-specific")
        navigate(page, "Maintenance & Calibration")
        page.screenshot(path=str(OUT / "05-laboratory-desktop.png"), full_page=True)
        page.set_viewport_size({"width": 390, "height": 844})
        page.screenshot(path=str(OUT / "06-mobile-workspace.png"), full_page=True)
        page.get_by_role("button", name="Open navigation", exact=True).click()
        expect(page.locator(".sidebar.open")).to_be_visible()
        page.get_by_role("button", name="Close navigation", exact=True).click()
        passed("Mobile navigation opens and closes without obscuring the active workspace")
        assert not errors, "Browser exceptions: " + str(errors)
        assert not server_errors, "Server errors: " + str(server_errors)
        assert not unexpected_dialogs, "Unexpected confirmation dialogs: " + str(unexpected_dialogs)
        passed("No browser exceptions, server failures or misleading post-save prompts")
        (OUT / "browser-results.json").write_text(json.dumps({"status": "passed", "checks": checks}, indent=2))
    except Exception as error:
        page.screenshot(path=str(OUT / "browser-failure.png"), full_page=True)
        (OUT / "browser-results.json").write_text(json.dumps({"status": "failed", "checks": checks, "error": str(error), "browserErrors": errors, "serverErrors": server_errors, "dialogs": unexpected_dialogs}, indent=2))
        raise
    finally:
        context.tracing.stop(path=str(OUT / "browser-trace.zip"))
        context.close()
        browser.close()
