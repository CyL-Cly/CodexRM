#!/usr/bin/env bash
set -euo pipefail
# Usage: sudo bash install-server.sh /path/codexrm-server /path/codexrm.service /path/web
binary=${1:?binary path required}
unit=${2:?systemd unit path required}
web=${3:?web directory path required}
root=/opt/codexrm
test "$(id -u)" = 0
test -f "$binary"
test -f "$unit"
test -f "$web/index.html"
getent passwd codexrm >/dev/null || useradd --system --home-dir "$root" --shell /usr/sbin/nologin codexrm
getent group codexrm >/dev/null || groupadd --system codexrm
install -d -m 0755 "$root" "$root/bin"
install -d -o codexrm -g codexrm -m 0700 "$root/data"
install -d -o codexrm -g codexrm -m 0700 "$root/data/updates"
if [ ! -f "$root/server.env" ]; then
    umask 077
    key=$(od -An -N32 -tx1 /dev/urandom | tr -d ' \n')
    printf 'CODEXRM_ENROLL_KEY=%s\n' "$key" > "$root/server.env"
fi
chmod 600 "$root/server.env"
install -m 0755 "$binary" "$root/bin/codexrm-server.next"
mv -f "$root/bin/codexrm-server.next" "$root/bin/codexrm-server"
web_next="$root/web.next"
rm -rf "$web_next"
cp -a "$web" "$web_next"
chmod -R u=rwX,go=rX "$web_next"
rm -rf "$root/web.previous"
if [ -d "$root/web" ]; then
    mv "$root/web" "$root/web.previous"
fi
mv "$web_next" "$root/web"
rm -rf "$root/web.previous"
install -m 0644 "$unit" /etc/systemd/system/codexrm.service
systemctl daemon-reload
systemctl enable codexrm.service
systemctl restart codexrm.service
for attempt in {1..20}; do
    if health_response=$(curl -fsS --connect-timeout 2 --max-time 5 -w $'\n%{http_code}' http://127.0.0.1:18080/api/v1/health); then
        health_status=${health_response##*$'\n'}
        health_body=${health_response%$'\n'*}
        if [[ "$health_status" == 2?? ]] && printf '%s' "$health_body" | grep -Eq '^[[:space:]]*\{[[:space:]]*"ok"[[:space:]]*:[[:space:]]*true[[:space:]]*,[[:space:]]*"service"[[:space:]]*:[[:space:]]*"codexrm"([[:space:]]*,[[:space:]]*"version"[[:space:]]*:[[:space:]]*"[^"]*")?[[:space:]]*\}[[:space:]]*$'; then
            printf '\nCodexRM ready on 127.0.0.1:18080\n'
            exit 0
        fi
    fi
    sleep 0.25
done
systemctl --no-pager --full status codexrm.service
exit 1
