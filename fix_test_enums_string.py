import os
import re

directory = 'C:/dev/finance-backend/src/test/java'

def process_file(filepath):
    with open(filepath, 'r', encoding='utf-8') as f:
        content = f.read()
    
    # Replace .setCategory(ExpenseCategory.XXX) with .setCategory(ExpenseCategory.XXX.name())
    new_content = re.sub(
        r'\.setCategory\((com\.finsight\.model\.)?ExpenseCategory\.([A-Z_]+)\)', 
        r'.setCategory(\1ExpenseCategory.\2.name())', 
        content
    )

    if new_content != content:
        with open(filepath, 'w', encoding='utf-8') as f:
            f.write(new_content)
        print(f"Updated {filepath}")

for root, _, files in os.walk(directory):
    for file in files:
        if file.endswith('.java'):
            process_file(os.path.join(root, file))
