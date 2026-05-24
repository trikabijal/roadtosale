#!/usr/bin/env python3
"""
Role-by-role E2E walkthrough — exercises the main flow each persona uses.
Runs against a live local backend (default http://localhost:8089).

Output: one pass/fail line per check, with a summary at the end. Exit code
non-zero on any failure. Suitable for CI smoke or before-demo verification.

Roles covered:
  - DEPT_ADMIN (KIA_SALES_DEPT_HEAD)            — BI dashboards + audit list/detail + intervention CRUD
  - DATA_VALIDATOR (KIA_SALES_AUDIT_DATA_VALIDATOR) — view inspection validation history
  - DATA_APPROVER (KIA_SALES_AUDIT_DATA_APPROVER)   — view inspection approval history
  - DEALER_PRINCIPAL (KIA_DEALER_PRINCIPAL_001) — myPlans + ack flow
  - OPERATOR (KIA_DEMO_AUDITOR_001)             — myAssignments + getChecksheet (mobile flow)

NOTE: this is an API walkthrough, not a UI click-through. The UI checklist
in dev/role-walkthrough-ui-checklist.md is the manual companion.
"""
from __future__ import annotations
import json, sys, urllib.request, urllib.error

BASE = "http://localhost:8089/api"
PASSWORD = "12345678"

# (role_label, username, device_type)
ROLES = [
    ("DEPT_ADMIN",       "KIA_SALES_DEPT_HEAD",            "WEB"),
    ("DATA_VALIDATOR",   "KIA_SALES_AUDIT_DATA_VALIDATOR", "WEB"),
    ("DATA_APPROVER",    "KIA_SALES_AUDIT_DATA_APPROVER",  "WEB"),
    ("DEALER_PRINCIPAL", "KIA_DEALER_PRINCIPAL_001",       "WEB"),
    ("OPERATOR",         "KIA_DEMO_AUDITOR_001",           "APP"),
]

results: list[tuple[bool, str]] = []
def check(ok: bool, label: str, detail: str = ""):
    line = ("PASS" if ok else "FAIL") + f"  {label}"
    if detail:
        line += f"  — {detail}"
    print(line)
    results.append((ok, label))

def http(method: str, path: str, token: str | None = None, body=None) -> tuple[int, dict]:
    url = f"{BASE}{path}" if path.startswith("/") else f"{BASE}/{path}"
    data = None if body is None else json.dumps(body).encode()
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", f"Bearer {token}")
    try:
        with urllib.request.urlopen(req, timeout=10) as resp:
            payload = json.loads(resp.read() or b"{}")
            return resp.status, payload
    except urllib.error.HTTPError as e:
        try:
            payload = json.loads(e.read() or b"{}")
        except Exception:
            payload = {}
        return e.code, payload

def login(username: str, device: str) -> str | None:
    code, body = http("POST", "/user/login", body={"username": username, "password": PASSWORD, "deviceType": device})
    if code != 200 or not body.get("status"):
        check(False, f"login {username}", f"http={code} msg={body.get('message')}")
        return None
    check(True, f"login {username}")
    return body["data"]["accessToken"]

# ─── Walkthroughs ─────────────────────────────────────────────────────────

def walk_dept_admin():
    print("\n=== DEPT_ADMIN (BI dashboards + audit + intervention CRUD) ===")
    t = login("KIA_SALES_DEPT_HEAD", "WEB")
    if not t: return

    code, body = http("GET", "/audit/list", t)
    audits = body.get("data", [])
    check(code == 200 and audits, "audit list", f"count={len(audits)}")
    if not audits: return
    audit = next((a for a in audits if a.get("totalLocations", 0) > 0), audits[0])
    aid = audit["id"]
    check(audit.get("totalLocations") is not None, "audit.totalLocations field present", f"={audit.get('totalLocations')}")

    code, body = http("GET", f"/audit/{aid}", t)
    d = body.get("data", {})
    check(code == 200 and "assignments" in d, "audit detail",
          f"assignments={len(d.get('assignments') or [])}")

    for level, suffix in [("national", "stats/national"), ("region/102", "stats/region/102"),
                          ("dealer/1322", "stats/dealer/1322"), ("location/10599", "stats/location/10599")]:
        code, body = http("GET", f"/audit/{aid}/{suffix}", t)
        d = body.get("data", {})
        check(code == 200 and "greenCount" in d, f"stats {level}",
              f"G={d.get('greenCount')} A={d.get('amberCount')} R={d.get('redCount')}")

    code, body = http("GET", f"/audit/{aid}/intervention-summary/national", t)
    summary = body.get("data", body)  # wrapped in ResponseDTO
    has_aliases = "activeCampaigns" in summary and "activeInterventions" in summary and "totalAssignments" in summary
    check(code == 200 and has_aliases, "intervention-summary national + aliases",
          f"active={summary.get('activeCampaigns')}, totalAssignments={summary.get('totalAssignments')}")

    code, body = http("GET", "/intervention/list", t)
    check(code == 200, "intervention list", f"count={len(body.get('data',[]))}")

    # Find an approved inspection for meta + overlay
    approved = next((a for a in d.get("assignments") or [] if a.get("userChecksheetStatus") == "APPROVED"), None)
    if approved:
        ucid = approved["inspectionId"]
        code, body = http("GET", f"/audit/userChecksheetMeta?userChecksheetId={ucid}", t)
        m = body.get("data", {})
        check(code == 200 and m.get("locationId") and m.get("dealerId") and m.get("score") is not None,
              "userChecksheetMeta", f"score={m.get('score')}")
        code, body = http("GET", f"/audit/userChecksheet/{ucid}/improvement-overlay", t)
        o = body.get("data", {})
        check(code == 200 and o.get("originalScore") is not None and o.get("currentScore") is not None,
              "improvement-overlay", f"original={o.get('originalScore')} current={o.get('currentScore')}")

def walk_validator():
    print("\n=== DATA_VALIDATOR ===")
    t = login("KIA_SALES_AUDIT_DATA_VALIDATOR", "WEB")
    if not t: return
    # The validator needs an inspection in SUBMITTED state to act on.
    # Read-side: pull validation history for any approved inspection — proves
    # the endpoint and DTO work; the create endpoint requires a SUBMITTED inspection
    # which the demo data lacks. Document this as a checklist item.
    code, body = http("POST", "/userChecksheetValidation/getUserChecksheetValidation",
                      t, body={"userChecksheetId": 39})
    d = body.get("data", {})
    check(code == 200 and "userChecksheetValidatorHistory" in d, "validation history shape",
          f"groups={len(d.get('userChecksheetValidatorHistory', {}))}")

def walk_approver():
    print("\n=== DATA_APPROVER ===")
    t = login("KIA_SALES_AUDIT_DATA_APPROVER", "WEB")
    if not t: return
    code, body = http("POST", "/userChecksheetApproval/getUserChecksheetApproval",
                      t, body={"userChecksheetId": 39})
    d = body.get("data", {})
    check(code == 200 and "userChecksheetApprovalHistory" in d, "approval history shape",
          f"status={d.get('checksheetStatus')}")

def walk_dealer_principal():
    print("\n=== DEALER_PRINCIPAL ===")
    t = login("KIA_DEALER_PRINCIPAL_001", "WEB")
    if not t: return
    code, body = http("GET", "/intervention-assignment/myPlans", t)
    plans = body.get("data", [])
    check(code == 200, "myPlans shape", f"count={len(plans)}")
    # If a plan exists, exercise ack:
    if plans:
        pid = plans[0]["id"]
        code, body = http("POST", f"/intervention-assignment/{pid}/acknowledge", t)
        d = body.get("data", {})
        check(code == 200 and d.get("acknowledgedAt") and d.get("acknowledgedByUserId"),
              "ack endpoint", f"acked_at={d.get('acknowledgedAt')}")
    else:
        check(True, "ack endpoint (skipped — no plans for this DP in demo data)",
              "create an intervention to surface ack")

def walk_operator():
    print("\n=== OPERATOR (mobile) ===")
    t = login("KIA_DEMO_AUDITOR_001", "APP")
    if not t: return
    code, body = http("GET", "/audit/myAssignments", t)
    assigns = body.get("data", [])
    check(code == 200 and assigns, "myAssignments", f"count={len(assigns)}")
    if not assigns: return
    a = assigns[0]
    check(a.get("assignmentKind") in ("audit", "intervention"), "assignmentKind discriminator",
          f"kind={a.get('assignmentKind')}")
    check(a.get("locationLabel"), "location label present", f"={a.get('locationLabel','')[:40]}")
    # If the assignment is already started, exercise getChecksheet:
    ucid = a.get("userChecksheetId")
    if ucid:
        code, body = http("POST", "/userChecksheet/getUserChecksheetWithAnswers",
                          t, body={"id": ucid})
        d = body.get("data", {})
        check(code == 200 and isinstance(d.get("chksContentData"), list),
              "getChecksheet shape", f"top-level zones={len(d.get('chksContentData', []))}")

# ─── Main ──────────────────────────────────────────────────────────────────

def main():
    print(f"Role walkthrough against {BASE}\n")
    walk_dept_admin()
    walk_validator()
    walk_approver()
    walk_dealer_principal()
    walk_operator()

    total = len(results)
    passed = sum(1 for ok, _ in results if ok)
    print(f"\n========== {passed}/{total} checks passed ==========")
    sys.exit(0 if passed == total else 1)

if __name__ == "__main__":
    main()
