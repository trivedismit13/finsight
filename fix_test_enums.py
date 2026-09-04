import os

directory = 'C:/dev/finance-backend/src/test/java'

replacements = {
    'ExpenseCategory.Food': 'ExpenseCategory.MEALS',
    'ExpenseCategory.Travel': 'ExpenseCategory.TRAVEL',
    'ExpenseCategory.Salary': 'ExpenseCategory.OTHER',
    'ExpenseCategory.Bonus': 'ExpenseCategory.OTHER',
    'ExpenseCategory.Entertainment': 'ExpenseCategory.OTHER',
    'ExpenseCategory.Transport': 'ExpenseCategory.TRANSPORT',
    'ExpenseCategory.TestCat': 'ExpenseCategory.OTHER'
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
