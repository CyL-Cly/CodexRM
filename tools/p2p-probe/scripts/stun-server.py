"""Temporary RFC 5389 binding/alternate-port responder, no business relay."""
import argparse, selectors, socket, struct, time

p = argparse.ArgumentParser()
p.add_argument('--ports', default='34780,34781')
p.add_argument('--seconds', type=int, default=7200)
a = p.parse_args()
sel = selectors.DefaultSelector()
socks = []
for port in map(int, a.ports.split(',')):
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    s.bind(('0.0.0.0', port))
    s.setblocking(False)
    socks.append(s)
    sel.register(s, selectors.EVENT_READ)
deadline = time.monotonic() + a.seconds
while time.monotonic() < deadline:
    for key, _ in sel.select(1):
        s = key.fileobj
        data, addr = s.recvfrom(2048)
        if len(data) < 20 or data[:2] != b'\x00\x01' or data[4:8] != b'\x21\x12\xa4\x42':
            continue
        changed = False
        pos = 20
        while pos + 4 <= len(data):
            typ, n = struct.unpack_from('!HH', data, pos)
            if typ == 3 and n == 4 and pos + 8 <= len(data):
                changed = bool(struct.unpack_from('!I', data, pos + 4)[0] & 2)
            pos += 4 + ((n + 3) & ~3)
        mapped = struct.pack('!BBH', 0, 1, addr[1] ^ 0x2112) + bytes(x ^ y for x, y in zip(socket.inet_aton(addr[0]), data[4:8]))
        payload = struct.pack('!HH', 0x0020, 8) + mapped
        out = socks[1 - socks.index(s)] if changed and len(socks) == 2 else s
        out.sendto(struct.pack('!HH', 0x0101, len(payload)) + data[4:20] + payload, addr)
