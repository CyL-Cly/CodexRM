"""Stop only this experiment's responder processes and remove its exact rules."""
import os, pathlib, signal, subprocess

root = pathlib.Path('/tmp/codexrm-p2p-20261118')
for filename in ['stun.pid', 'standard.pid']:
    pidfile = root / filename
    if not pidfile.exists():
        continue
    pid = int(pidfile.read_text().strip())
    proc = pathlib.Path(f'/proc/{pid}/cmdline')
    if proc.exists():
        command = proc.read_bytes().replace(b'\x00', b' ').decode(errors='replace')
        if str(root / 'stun-server.py') not in command:
            raise RuntimeError(f'Refusing to stop unrelated PID {pid}')
        os.kill(pid, signal.SIGTERM)
        print(f'stopped experiment responder {pid}')
for ports, comment in [('34780:34781', 'codexrm-p2p-20261118'), ('3478:3479', 'codexrm-p2p-standard-20261118')]:
    rule = ['INPUT', '-p', 'udp', '--dport', ports, '-m', 'comment', '--comment', comment, '-j', 'ACCEPT']
    if subprocess.run(['iptables', '-C', *rule], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL).returncode == 0:
        subprocess.run(['iptables', '-D', *rule], check=True)
        print(f'removed experiment rule {comment}')
