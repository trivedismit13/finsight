import os

directory = 'C:/dev/finance-backend/src'
replacements = {
    'Role.VIEWER': 'Role.EMPLOYEE',
    'Role.ADMIN': 'Role.FINANCE_ADMIN',
    'Role.ANALYST': 'Role.MANAGER',
    '"VIEWER"': '"EMPLOYEE"',
    '"ADMIN"': '"FINANCE_ADMIN"',
    '"ANALYST"': '"MANAGER"',
    'viewer@': 'employee@',
    'admin@': 'finance_admin@',
    'analyst@': 'manager@',
}

for root, _, files in os.walk(directory):
    for file in files:
        if file.endswith('.java'):
            filepath = os.path.join(root, file)
            with open(filepath, 'r', encoding='utf-8') as f:
                content = f.read()
            
            new_content = content
            for old, new in replacements.items():
                new_content = new_content.replace(old, new)
                
            if new_content != content:
                with open(filepath, 'w', encoding='utf-8') as f:
                    f.write(new_content)
                print(f"Updated {filepath}")
