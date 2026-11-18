import json, pathlib, subprocess, sys

root = pathlib.Path(sys.argv[1])
def adb(*args):
    return subprocess.check_output(['adb', 'shell', *args], text=True, encoding='utf-8', errors='replace').strip()
pid = adb('pidof', 'com.codexrm.p2pprobe')
fds = adb('run-as', 'com.codexrm.p2pprobe', 'ls', f'/proc/{pid}/fd')
memory = adb('dumpsys', 'meminfo', 'com.codexrm.p2pprobe')
result = {
    'pid': pid,
    'fdSample': len(fds.splitlines()),
    'memoryLines': [line.strip() for line in memory.splitlines() if 'TOTAL PSS' in line or 'Native Heap:' in line],
}
destination = root / sys.argv[2]
destination.write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
print(json.dumps(result))
