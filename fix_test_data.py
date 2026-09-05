import os

def fix_expense_service_test():
    path = 'C:\\dev\\finance-backend\\src\\test\\java\\com\\finsight\\service\\ExpenseServiceTest.java'
    with open(path, 'r', encoding='utf-8') as f:
        content = f.read()

    # Add setCategory to saved1
    content = content.replace('saved1.setAmount(req.getAmount());', 'saved1.setAmount(req.getAmount());\n        saved1.setCategory(com.finsight.model.ExpenseCategory.OTHER);\n        saved1.setExpenseDate(LocalDate.now());')
    
    # Add setCategory to saved2
    content = content.replace('saved2.setAmount(req.getAmount());', 'saved2.setAmount(req.getAmount());\n        saved2.setCategory(com.finsight.model.ExpenseCategory.OTHER);\n        saved2.setExpenseDate(LocalDate.now());')

    with open(path, 'w', encoding='utf-8') as f:
        f.write(content)
    print(f"Updated {path}")

if __name__ == '__main__':
    fix_expense_service_test()
