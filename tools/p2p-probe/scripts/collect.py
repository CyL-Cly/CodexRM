import hashlib, json, pathlib, subprocess, sys

directory = pathlib.Path(sys.argv[1])
directory.mkdir(parents=True, exist_ok=True)
for remote, local in [('results.json', 'phone-results.json'), ('network.txt', 'phone-network-start.txt')]:
    data = subprocess.check_output(['adb', 'exec-out', 'run-as', 'com.codexrm.p2pprobe', 'cat', f'files/{remote}'])
    (directory / local).write_bytes(data)
result = json.loads((directory / 'phone-results.json').read_text(encoding='utf-8-sig'))
phone = result.get('results', [])
host = [json.loads(line) for line in (directory / 'rounds.jsonl').read_text(encoding='utf-8-sig').splitlines() if line.strip()]
paired_phone = {row['round']: row for row in host if row['role'] == 'phone'}
if result.get('error'):
    raise RuntimeError(result['error'])
if len(phone) != len(paired_phone) or any(row != paired_phone.get(row['round']) for row in phone):
    raise RuntimeError('Phone-local evidence does not match coordinator evidence')
print(json.dumps({'verifiedPhoneLocalRounds':len(phone), 'phoneEvidenceSHA256':hashlib.sha256((directory/'phone-results.json').read_bytes()).hexdigest()}))
