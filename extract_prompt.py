import json
import sys

log_file = r'C:\Users\Smit\.gemini\antigravity\brain\e808b96a-73d9-482b-bf3c-312daa7d4976\.system_generated\logs\transcript_full.jsonl'
with open(log_file, 'r', encoding='utf-8') as f:
    lines = f.readlines()

for line in reversed(lines):
    data = json.loads(line)
    if data.get('type') == 'USER_INPUT':
        with open('C:\\dev\\finance-backend\\extracted_prompt.txt', 'w', encoding='utf-8') as out:
            out.write(data.get('content', ''))
        print("Successfully extracted prompt.")
        break
