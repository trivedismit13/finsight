import os
import re

directory = 'C:/dev/finance-backend/src/test/java'

replacements = {
    'getRecordId()': 'getExpenseId()',
    'setRecordId(': 'setExpenseId(',
    'getRecordDate()': 'getExpenseDate()',
    'setRecordDate(': 'setExpenseDate(',
    # setType("INCOME") -> removed or replaced with setStatus
    # But wait, type doesn't exist anymore, it's just status and category.
    # If a test does r1.setType("INCOME"); we can change it to r1.setStatus(ExpenseStatus.APPROVED); or similar? 
    # Or just remove setType and fix category: r1.setCategory(ExpenseCategory.TRAVEL)
}

def process_file(filepath):
    with open(filepath, 'r', encoding='utf-8') as f:
        content = f.read()
    
    new_content = content
    for old, new in replacements.items():
        new_content = new_content.replace(old, new)
    
    # regex for setType
    new_content = re.sub(r'([a-zA-Z0-9_]+)\.setType\(.*?\);\s*', '', new_content)
    
    # fix setCategory("TRAVEL") -> setCategory(ExpenseCategory.TRAVEL)
    new_content = re.sub(r'([a-zA-Z0-9_]+)\.setCategory\("([^"]+)"\)', r'\1.setCategory(com.finsight.model.ExpenseCategory.\2)', new_content)

    if new_content != content:
        with open(filepath, 'w', encoding='utf-8') as f:
            f.write(new_content)
        print(f"Updated {filepath}")

for root, _, files in os.walk(directory):
    for file in files:
        if file.endswith('.java'):
            process_file(os.path.join(root, file))
