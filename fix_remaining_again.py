import os

def fix_remaining_tests_again():
    # Fix ReportFailureInjectionTest
    path = 'C:\\dev\\finance-backend\\src\\test\\java\\com\\finsight\\controller\\ReportFailureInjectionTest.java'
    with open(path, 'r', encoding='utf-8') as f:
        content = f.read()
    new_content = content.replace('ROLE_MANAGER', 'ROLE_FINANCE_ADMIN')
    if content != new_content:
        with open(path, 'w', encoding='utf-8') as f:
            f.write(new_content)
        print(f"Updated {path}")
        
    # Fix PaginationFilterTest
    path = 'C:\\dev\\finance-backend\\src\\test\\java\\com\\finsight\\controller\\PaginationFilterTest.java'
    with open(path, 'r', encoding='utf-8') as f:
        content = f.read()
    new_content = content.replace('r2.setCategory(com.finsight.model.ExpenseCategory.OTHER);\n        r2.setExpenseDate(LocalDate.of(2026, 8, 15));\n        r2.setCreatedBy(testUser);', 'r2.setCategory(com.finsight.model.ExpenseCategory.OTHER);\n        r2.setExpenseDate(LocalDate.of(2026, 8, 15));\n        r2.setCreatedBy(testUser);\n        r2.setStatus(com.finsight.model.ExpenseStatus.APPROVED);')
    if content != new_content:
        with open(path, 'w', encoding='utf-8') as f:
            f.write(new_content)
        print(f"Updated {path}")

if __name__ == '__main__':
    fix_remaining_tests_again()
