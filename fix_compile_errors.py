import os
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
    c = c.replace('getRecordId()', 'getExpenseId()')
    c = c.replace('getRecordDate()', 'getExpenseDate()')
    c = c.replace('setRecordDate(', 'setExpenseDate(')
    c = re.sub(r'record\.setType\(req\.getType\(\)\);\s*', '', c)
    c = c.replace('req.getCategory()', 'ExpenseCategory.valueOf(req.getCategory())')
    c = c.replace('ExpenseCategory.valueOf(ExpenseCategory.valueOf(req.getCategory()))', 'ExpenseCategory.valueOf(req.getCategory())')
    # For existing/r in validation where it checks type:
    c = c.replace('existing.getType()', 'existing.getStatus().name()')
    c = c.replace('r.getType()', 'r.getStatus().name()')
    
    if 'import com.finsight.model.ExpenseCategory;' not in c:
        c = c.replace('import com.finsight.model.Expense;', 'import com.finsight.model.Expense;\nimport com.finsight.model.ExpenseCategory;')
    return c

def fix_report_export_service(c):
    c = c.replace('getRecordId()', 'getExpenseId()')
    c = c.replace('getRecordDate()', 'getExpenseDate()')
    c = c.replace('record.getType()', 'record.getStatus().name()')
    return c

process_file('C:/dev/finance-backend/src/main/java/com/finsight/service/ExpenseService.java', fix_expense_service)
process_file('C:/dev/finance-backend/src/main/java/com/finsight/service/ReportExportService.java', fix_report_export_service)
process_file('C:/dev/finance-backend/src/main/java/com/finsight/dto/response/ExpenseResponse.java', lambda c: c.replace('getRecordId()', 'getExpenseId()').replace('getRecordDate()', 'getExpenseDate()'))
