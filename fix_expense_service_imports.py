import os

filepath = 'C:/dev/finance-backend/src/main/java/com/finsight/service/ExpenseService.java'
with open(filepath, 'r', encoding='utf-8') as f:
    content = f.read()

# Fix imports
if 'import com.finsight.model.ExpenseStatus;' not in content:
    content = content.replace('import com.finsight.model.ExpenseCategory;', 'import com.finsight.model.ExpenseCategory;\nimport com.finsight.model.ExpenseStatus;')
if 'import java.time.LocalDateTime;' not in content:
    content = content.replace('import java.time.LocalDate;', 'import java.time.LocalDate;\nimport java.time.LocalDateTime;')
if 'import org.springframework.security.access.AccessDeniedException;' not in content:
    content = content.replace('import org.springframework.stereotype.Service;', 'import org.springframework.stereotype.Service;\nimport org.springframework.security.access.AccessDeniedException;')

# Fix OptimisticLocking
old_lock = 'validateNoConcurrentModification(record, req.getVersion());'
new_lock = '''if (!record.getVersion().equals(req.getVersion())) {
            throw new org.springframework.dao.OptimisticLockingFailureException("Record " + id + " was modified by another user. Client has version=" + req.getVersion() + " but current is version=" + record.getVersion() + ". Please refresh and retry.");
        }'''
content = content.replace(old_lock, new_lock)

with open(filepath, 'w', encoding='utf-8') as f:
    f.write(content)
