import requests
import time
import sys

BASE_URL = "http://localhost:8080"
headers = {"Content-Type": "application/json"}

print("Starting E2E Expense Workflow Verification...")

def register(email, name, role):
    res = requests.post(f"{BASE_URL}/api/auth/register", json={
        "name": name,
        "email": email,
        "password": "password123",
        "role": role # Usually backend ignores this and defaults to VIEWER/EMPLOYEE, we might need a DB script to set them up
    })
    return res

def login(email, password="password123"):
    return requests.post(f"{BASE_URL}/api/auth/login", json={
        "email": email,
        "password": password
    })

# We'll use a mysql script to patch the roles because the register endpoint hardcodes EMPLOYEE (or we can just use python mysql connector)
import subprocess

def run_sql(query):
    # Using mysql command line since we are on windows
    subprocess.run(["mysql", "-u", "root", "-proot", "finsight", "-e", query], check=True)

try:
    print("1. Setup users")
    register("employee@example.com", "Emp", "EMPLOYEE")
    register("manager@example.com", "Mgr", "MANAGER")
    register("finance@example.com", "Fin", "FINANCE_ADMIN")
    register("admin@example.com", "Adm", "ADMIN")

    # Patch roles directly in DB
    run_sql("UPDATE users SET role = 'MANAGER' WHERE email = 'manager@example.com';")
    run_sql("UPDATE users SET role = 'FINANCE_ADMIN' WHERE email = 'finance@example.com';")
    run_sql("UPDATE users SET role = 'ADMIN' WHERE email = 'admin@example.com';")
    run_sql("UPDATE users SET role = 'EMPLOYEE' WHERE email = 'employee@example.com';")

    # Set manager hierarchy
    run_sql("""
        UPDATE users e 
        JOIN users m ON m.email = 'manager@example.com'
        SET e.manager_id = m.user_id 
        WHERE e.email = 'employee@example.com';
    """)

    print("2. Login each user")
    emp_token = login("employee@example.com").json().get("accessToken")
    mgr_token = login("manager@example.com").json().get("accessToken")
    fin_token = login("finance@example.com").json().get("accessToken")
    adm_token = login("admin@example.com").json().get("accessToken")

    print(f"Tokens obtained: EMP:{bool(emp_token)}, MGR:{bool(mgr_token)}, FIN:{bool(fin_token)}, ADM:{bool(adm_token)}")

    print("\n3. Viewer (Employee) attempts to create a budget (Should be 403)")
    res = requests.post(f"{BASE_URL}/api/analytics/budgets", 
                        headers={"Authorization": f"Bearer {emp_token}"},
                        json={"category": "MEALS", "monthYear": "2026-10", "budgetAmount": 1000})
    print(f"Create Budget as Employee: {res.status_code} (Expected 403)")

    print("\n4. Admin creates an expense (with idempotency key)")
    expense_payload = {
        "amount": 5000.0,
        "category": "MEALS",
        "expenseDate": "2026-10-01",
        "description": "Food",
        "idempotencyKey": "idem-key-123"
    }
    res1 = requests.post(f"{BASE_URL}/api/expenses", json=expense_payload, headers={"Authorization": f"Bearer {adm_token}"})
    print(f"First create returned: {res1.status_code}")
    
    print("\n5. Repeat the exact request (idempotency check)")
    res2 = requests.post(f"{BASE_URL}/api/expenses", json=expense_payload, headers={"Authorization": f"Bearer {adm_token}"})
    print(f"Second create (same key) returned: {res2.status_code} (Should be 201 or 200, matching original record)")

    print("\n6. Repeat using same key but different payload")
    expense_payload2 = expense_payload.copy()
    expense_payload2["amount"] = 8000.0
    res3 = requests.post(f"{BASE_URL}/api/expenses", json=expense_payload2, headers={"Authorization": f"Bearer {adm_token}"})
    print(f"Third create (same key, diff payload) returned: {res3.status_code} (Should return original record, amount will still be 5000)")
    
    # Verify Audit exists
    print("Checking Audit logs for Admin:")
    audit_res = requests.get(f"{BASE_URL}/api/admin/audit", headers={"Authorization": f"Bearer {adm_token}"})
    print(f"Audit log fetch returned: {audit_res.status_code}")
    
    print("\n7. Admin creates a budget")
    budget_res = requests.post(f"{BASE_URL}/api/analytics/budgets", 
                        headers={"Authorization": f"Bearer {adm_token}"},
                        json={"category": "MEALS", "monthYear": "2026-10", "budgetAmount": 500})
    print(f"Budget create returned: {budget_res.status_code}")

    print("Wait a few seconds for notification to process...")
    time.sleep(3)

    print("\n9. Generate a report as Analyst (Finance Admin)")
    report_res = requests.post(f"{BASE_URL}/api/analytics/company/reports", 
                            headers={"Authorization": f"Bearer {fin_token}"},
                            params={"period": "2026-10"})
    print(f"Report request returned: {report_res.status_code} (Expected 202)")
    
    if report_res.status_code == 202:
        job_id = report_res.json().get("data", {}).get("jobId")
        print(f"Job ID: {job_id}")
        
        print("\n10. Verify report completion")
        time.sleep(2)
        status_res = requests.get(f"{BASE_URL}/api/analytics/company/reports/{job_id}/status", headers={"Authorization": f"Bearer {fin_token}"})
        print(f"Status check returned: {status_res.status_code} - State: {status_res.json().get('data', {}).get('status')}")

        print("\n11. Attempt to download another user's report (as employee)")
        bad_download = requests.get(f"{BASE_URL}/api/analytics/company/reports/{job_id}/download", headers={"Authorization": f"Bearer {emp_token}"})
        print(f"Employee download returned: {bad_download.status_code} (Expected 403)")

        print("Downloading as Finance Admin...")
        good_download = requests.get(f"{BASE_URL}/api/analytics/company/reports/{job_id}/download", headers={"Authorization": f"Bearer {fin_token}"})
        print(f"Finance Admin download returned: {good_download.status_code} (Expected 200)")

    print("\nComplete.")
except Exception as e:
    print(f"Error: {e}")

