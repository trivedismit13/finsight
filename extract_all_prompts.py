import json

log_file = r'C:\Users\Smit\.gemini\antigravity\brain\e808b96a-73d9-482b-bf3c-312daa7d4976\.system_generated\logs\transcript_full.jsonl'
output_file = r'C:\dev\finance-backend\all_user_inputs.txt'

with open(log_file, 'r', encoding='utf-8') as f:
    lines = f.readlines()

with open(output_file, 'w', encoding='utf-8') as out:
    for line in lines:
        try:
            data = json.loads(line)
            if data.get('type') == 'USER_INPUT':
                out.write("==== USER INPUT ====\n")
                out.write(data.get('content', ''))
                out.write("\n====================\n\n")
        except:
            pass
