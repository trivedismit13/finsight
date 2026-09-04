import os

directory = 'C:/dev/finance-backend/src'

replacements = {
    'CreateRecordRequest': 'CreateExpenseRequest',
    'UpdateRecordRequest': 'UpdateExpenseRequest',
    'RecordResponse': 'ExpenseResponse'
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
