import re
import os

log_file = 'C:/Users/Smit/.gemini/antigravity/brain/e808b96a-73d9-482b-bf3c-312daa7d4976/.system_generated/tasks/task-6603.log'

with open(log_file, 'r', encoding='utf-8') as f:
    log_content = f.read()

# Pattern: [ERROR] /C:/dev/finance-backend/src/test/java/com/finsight/service/DashboardServiceTest.java:[60,71] incompatible types: java.lang.String cannot be converted to com.finsight.model.ExpenseCategory
pattern = re.compile(r'\[ERROR\] /(C:/dev/finance-backend/[^:]+):\[(\d+),\d+\] incompatible types: java\.lang\.String cannot be converted to com\.finsight\.model\.ExpenseCategory')
utilities_pattern = re.compile(r'\[ERROR\] /(C:/dev/finance-backend/[^:]+):\[(\d+),\d+\] cannot find symbol\s*\[ERROR\]\s*symbol:\s*variable Utilities')

fixes = {} # filepath -> list of lines to fix (1-indexed)

for match in pattern.finditer(log_content):
    filepath = match.group(1)
    line_num = int(match.group(2))
    if filepath not in fixes:
        fixes[filepath] = []
    fixes[filepath].append(line_num)

util_fixes = {}
for match in utilities_pattern.finditer(log_content):
    filepath = match.group(1)
    line_num = int(match.group(2))
    if filepath not in util_fixes:
        util_fixes[filepath] = []
    util_fixes[filepath].append(line_num)

for filepath, lines in fixes.items():
    with open(filepath, 'r', encoding='utf-8') as f:
        file_lines = f.readlines()
    
    for line_num in lines:
        idx = line_num - 1
        file_lines[idx] = file_lines[idx].replace('.name()', '')
        
    with open(filepath, 'w', encoding='utf-8') as f:
        f.writelines(file_lines)
    print(f"Fixed string cast in {filepath}")

for filepath, lines in util_fixes.items():
    with open(filepath, 'r', encoding='utf-8') as f:
        file_lines = f.readlines()
    
    for line_num in lines:
        idx = line_num - 1
        file_lines[idx] = file_lines[idx].replace('Utilities', 'OTHER')
        
    with open(filepath, 'w', encoding='utf-8') as f:
        f.writelines(file_lines)
    print(f"Fixed Utilities cast in {filepath}")
