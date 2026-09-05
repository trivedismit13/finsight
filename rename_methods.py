import os
import re

def rename_methods():
    for root, dirs, files in os.walk('C:\\dev\\finance-backend\\src'):
        for file in files:
            if file.endswith('.java'):
                path = os.path.join(root, file)
                with open(path, 'r', encoding='utf-8') as f:
                    content = f.read()

                # Rename the specific methods
                new_content = content.replace('createRecord(', 'createExpense(')
                new_content = new_content.replace('updateRecord(', 'updateExpense(')
                new_content = new_content.replace('deleteRecord(', 'deleteExpense(')
                new_content = new_content.replace('getAllRecords(', 'getAllExpenses(')
                new_content = new_content.replace('saveRecordRequiresNew(', 'saveExpenseRequiresNew(')
                new_content = new_content.replace('doCreateRecord(', 'doCreateExpense(')

                if content != new_content:
                    with open(path, 'w', encoding='utf-8') as f:
                        f.write(new_content)
                    print(f"Updated {path}")

if __name__ == '__main__':
    rename_methods()
