import os
import shutil

directory = 'C:/dev/finance-backend/src'

replacements = {
    'FinancialRecord': 'Expense',
    'financialRecord': 'expense',
    'financial_records': 'expenses',
    'record_id': 'expense_id',
    'record_date': 'expense_date',
    'recordId': 'expenseId',
    'recordDate': 'expenseDate',
    'CategoryBudget': 'Budget',
    'category_budgets': 'budgets',
    'CategoryBudgetRepository': 'BudgetRepository',
    'CategoryBudgetService': 'BudgetService'
}

# First rename files
for root, _, files in os.walk(directory):
    for file in files:
        if 'FinancialRecord' in file:
            old_path = os.path.join(root, file)
            new_path = os.path.join(root, file.replace('FinancialRecord', 'Expense'))
            shutil.move(old_path, new_path)
            print(f"Renamed {old_path} to {new_path}")
        elif 'CategoryBudget' in file:
            old_path = os.path.join(root, file)
            new_path = os.path.join(root, file.replace('CategoryBudget', 'Budget'))
            shutil.move(old_path, new_path)
            print(f"Renamed {old_path} to {new_path}")

# Then replace content in all java files
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
