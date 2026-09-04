import re

def process_file(filepath, callback):
    with open(filepath, 'r', encoding='utf-8') as f:
        content = f.read()
    new_content = callback(content)
    if new_content != content:
        with open(filepath, 'w', encoding='utf-8') as f:
            f.write(new_content)
        print(f"Updated {filepath}")

def fix_expense_service(c):
    c = c.replace('.type(record.getType())', '.status(record.getStatus().name())')
    c = c.replace('.category(record.getCategory())', '.category(record.getCategory().name())')
    c = c.replace('r.getCategory()', 'r.getCategory().name()')
    return c

def fix_report_export(c):
    c = c.replace('record.getCategory()', 'record.getCategory().name()')
    return c

process_file('C:/dev/finance-backend/src/main/java/com/finsight/service/ExpenseService.java', fix_expense_service)
process_file('C:/dev/finance-backend/src/main/java/com/finsight/service/ReportExportService.java', fix_report_export)
