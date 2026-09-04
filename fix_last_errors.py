import os

# Fix DashboardServiceTest
filepath = 'C:/dev/finance-backend/src/test/java/com/finsight/service/DashboardServiceTest.java'
with open(filepath, 'r', encoding='utf-8') as f:
    lines = f.readlines()
lines[107] = lines[107].replace('com.finsight.model.ExpenseCategory.OTHER', 'com.finsight.model.ExpenseCategory.OTHER.name()')
with open(filepath, 'w', encoding='utf-8') as f:
    f.writelines(lines)

# Fix CrossUserAuthorizationTest
filepath = 'C:/dev/finance-backend/src/test/java/com/finsight/security/CrossUserAuthorizationTest.java'
with open(filepath, 'r', encoding='utf-8') as f:
    lines = f.readlines()
lines[87] = lines[87].replace('com.finsight.model.ExpenseCategory.MEALS.name()', 'com.finsight.model.ExpenseCategory.MEALS')
with open(filepath, 'w', encoding='utf-8') as f:
    f.writelines(lines)
