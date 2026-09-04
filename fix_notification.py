import os
import re

files = [
    'src/test/java/com/finsight/service/DatabaseConcurrencyTest.java',
    'src/test/java/com/finsight/service/FailureInjectionDbTest.java',
    'src/test/java/com/finsight/service/FailureInjectionNotificationTest.java'
]

for f in files:
    with open(f, 'r') as file:
        content = file.read()
    
    content = re.sub(r'Notification\s+([a-zA-Z0-9_]+)\s*=\s*new\s+Notification\(\);', r'Notification \1 = new Notification();\n        \1.setType("TEST_ALERT");', content)
    
    with open(f, 'w') as file:
        file.write(content)
