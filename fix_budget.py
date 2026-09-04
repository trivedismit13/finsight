with open('src/test/java/com/finsight/service/BudgetServiceTest.java', 'r') as f:
    content = f.read()
content = content.replace('eq("SOFTWARE")', 'eq(com.finsight.model.ExpenseCategory.SOFTWARE)')
with open('src/test/java/com/finsight/service/BudgetServiceTest.java', 'w') as f:
    f.write(content)
