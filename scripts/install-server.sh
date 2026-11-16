#!/usr/bin/env bash
set -euo pipefail
# Usage: sudo bash install-server.sh /path/codexrm-server /path/codexrm.service
binary=${1:?binary path required}
unit=${2:?systemd unit path required}
root=/opt/codexrm
test "$(id -u)" = 0
test -f "$binary"
test -f "$unit"
getent passwd codexrm >/dev/null || useradd --system --home-dir "$root" --shell /usr/sbin/nologin codexrm
install -d -m 0755 "$root" "$root/bin"
install -d -o codexrm -g codexrm -m 0700 "$root/data"
if [ ! -f "$root/server.env" ]; then
    umask 077
    key=$(od -An -N32 -tx1 /dev/urandom | tr -d ' \n')
    printf 'CODEXRM_ENROLL_KEY=%s\n' "$key" > "$root/server.env"
fi
chmod 600 "$root/server.env"
install -m 0755 "$binary" "$root/bin/codexrm-server.next"
mv -f "$root/bin/codexrm-server.next" "$root/bin/codexrm-server"
install -m 0644 "$unit" /etc/systemd/system/codexrm.service
systemctl daemon-reload
systemctl enable codexrm.service
systemctl restart codexrm.service
for attempt in {1..20}; do
    if curl -fsS http://127.0.0.1:18080/api/v1/health; then
        printf '\nCodexRM ready on 127.0.0.1:18080\n'
        exit 0
    fi
    sleep 0.25
done
systemctl --no-pager --full status codexrm.service
exit 1
