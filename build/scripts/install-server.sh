#!/usr/bin/env bash
set -euo pipefail

usage() {
    printf '%s\n' \
        'Install from a Server package: sudo bash install-server.sh' \
        'Install from separate files: sudo bash install-server.sh /path/codexrm-server /path/codexrm.service /path/web'
}

if [[ $# == 0 ]]; then
    package_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
    binary="$package_dir/codexrm-server"
    unit="$package_dir/deploy/codexrm.service"
    web="$package_dir/web"
elif [[ $# == 3 ]]; then
    binary=$1
    unit=$2
    web=$3
    package_dir=$(dirname -- "$binary")
elif [[ $# == 1 && ( "$1" == --help || "$1" == -h ) ]]; then
    usage
    exit 0
else
    usage >&2
    exit 2
fi

for file in "$binary" "$unit" "$web/index.html"; do
    if [[ ! -f "$file" ]]; then
        printf 'Required file missing: %s\n' "$file" >&2
        usage >&2
        exit 1
    fi
done
updater="$package_dir/codexrm-server-updater"
update_enabled=false
if [[ -f "$updater" ]]; then
    for file in "$package_dir/deploy/"codexrm-server-update.{service,timer,env.example}; do
        if [[ ! -f "$file" ]]; then
            printf 'Required updater file missing: %s\n' "$file" >&2
            exit 1
        fi
    done
    update_enabled=true
fi
if [[ $(id -u) != 0 ]]; then
    printf 'Run this installer with sudo.\n' >&2
    exit 1
fi

root=/opt/codexrm
getent group codexrm >/dev/null || groupadd --system codexrm
getent passwd codexrm >/dev/null || useradd --system --gid codexrm --home-dir "$root" --shell /usr/sbin/nologin codexrm
install -d -m 0755 "$root" "$root/bin"
exec 9>"$root/.install.lock"
flock -n 9 || { printf 'Another CodexRM installation is in progress.\n' >&2; exit 1; }
install -d -o codexrm -g codexrm -m 0700 "$root/data"
install -d -o codexrm -g codexrm -m 0700 "$root/data/updates"
# Do not put a root updater's trust source in the relay account's writable data directory.
if [[ -L "$root/server-updates" ]]; then
    printf 'The Server manifest directory must not be a symlink.\n' >&2
    exit 1
fi
install -d -o root -g root -m 0755 "$root/server-updates"
if [ ! -f "$root/server.env" ]; then
    umask 077
    key=$(od -An -N32 -tx1 /dev/urandom | tr -d ' \n')
    printf 'CODEXRM_ENROLL_KEY=%s\n' "$key" > "$root/server.env"
fi
chmod 600 "$root/server.env"
service=codexrm.service
unit_target="/etc/systemd/system/$service"
binary_target="$root/bin/codexrm-server"
web_target="$root/web"
stage=$(mktemp -d "$root/.install.XXXXXXXX")
unit_next=""
binary_changed=false
web_changed=false
unit_changed=false
restart_attempted=false
enable_attempted=false
was_active=false
was_enabled=false
if systemctl is-active --quiet "$service"; then was_active=true; fi
if systemctl is-enabled --quiet "$service"; then was_enabled=true; fi

restore_file() {
    local backup=$1 destination=$2
    if [[ -e "$backup" || -L "$backup" ]]; then
        mv -fT -- "$backup" "$destination"
    else
        rm -f -- "$destination"
    fi
}

finish_install() {
    local status=$? restored=true
    trap - EXIT
    # Let rollback finish if the installer was interrupted.
    trap '' INT TERM
    if (( status != 0 )); then
        if $restart_attempted; then systemctl stop "$service" || restored=false; fi
        if $enable_attempted && ! $was_enabled; then systemctl disable "$service" || restored=false; fi
        if $binary_changed; then restore_file "$stage/binary.previous" "$binary_target" || restored=false; fi
        if $web_changed || [[ -e "$stage/web.previous" || -L "$stage/web.previous" ]]; then
            rm -rf -- "$web_target" || restored=false
            if [[ -e "$stage/web.previous" || -L "$stage/web.previous" ]]; then
                mv -T -- "$stage/web.previous" "$web_target" || restored=false
            fi
        fi
        if $unit_changed; then
            if [[ -e "$stage/unit.previous" || -L "$stage/unit.previous" ]]; then
                # Stage on the unit filesystem so the final rename stays atomic.
                cp -aT -- "$stage/unit.previous" "$unit_next" && mv -fT -- "$unit_next" "$unit_target" || restored=false
            else
                rm -f -- "$unit_target" || restored=false
            fi
            systemctl daemon-reload || restored=false
        fi
        if $restart_attempted && $was_active; then systemctl restart "$service" || restored=false; fi
        if $restored; then
            printf 'Installation failed. Previous files and service state restored.\n' >&2
        else
            printf 'Installation and rollback failed. Remaining backups are in %s\n' "$stage" >&2
        fi
    fi
    if [[ -n "$unit_next" ]]; then rm -f -- "$unit_next"; fi
    if $restored; then rm -rf -- "$stage"; fi
    exit "$status"
}
trap finish_install EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

install -m 0755 "$binary" "$stage/binary.next"
cp -a -- "$web" "$stage/web.next"
chmod -R u=rwX,go=rX "$stage/web.next"
if [[ -L "$web_target/release-history.json" && ! -e "$stage/web.next/release-history.json" && ! -L "$stage/web.next/release-history.json" ]]; then
    cp -P -- "$web_target/release-history.json" "$stage/web.next/release-history.json"
fi
unit_next=$(mktemp "${unit_target}.XXXXXXXX")
install -m 0644 "$unit" "$unit_next"
if [[ -e "$binary_target" || -L "$binary_target" ]]; then cp -a -- "$binary_target" "$stage/binary.previous"; fi
if [[ -e "$unit_target" || -L "$unit_target" ]]; then cp -a -- "$unit_target" "$stage/unit.previous"; fi

binary_changed=true
mv -fT -- "$stage/binary.next" "$binary_target"
if [[ -e "$web_target" || -L "$web_target" ]]; then mv -T -- "$web_target" "$stage/web.previous"; fi
web_changed=true
mv -T -- "$stage/web.next" "$web_target"
unit_changed=true
mv -fT -- "$unit_next" "$unit_target"
systemctl daemon-reload
restart_attempted=true
systemctl restart "$service"
ready=false
for attempt in {1..20}; do
    if health_response=$(curl -fsS --connect-timeout 2 --max-time 5 -w $'\n%{http_code}' http://127.0.0.1:18080/api/v1/health); then
        health_status=${health_response##*$'\n'}
        health_body=${health_response%$'\n'*}
        if [[ "$health_status" == 2?? ]] && printf '%s' "$health_body" | grep -Eq '^[[:space:]]*\{[[:space:]]*"ok"[[:space:]]*:[[:space:]]*true[[:space:]]*,[[:space:]]*"service"[[:space:]]*:[[:space:]]*"codexrm"([[:space:]]*,[[:space:]]*"version"[[:space:]]*:[[:space:]]*"[^"]*")?(,[[:space:]]*"serverVersionCode"[[:space:]]*:[[:space:]]*[0-9]+,[[:space:]]*"serverVersionName"[[:space:]]*:[[:space:]]*"[0-9A-Za-z.+-]+")?[[:space:]]*\}[[:space:]]*$'; then
            if ! $was_enabled; then
                enable_attempted=true
                systemctl enable "$service"
            fi
            printf '\nCodexRM ready on 127.0.0.1:18080\n'
            ready=true
            break
        fi
    fi
    sleep 0.25
done
if ! $ready; then
    systemctl --no-pager --full status "$service" || true
    exit 1
fi

# Commit the working relay before installing the independent root-owned scheduler.
trap - EXIT INT TERM
rm -rf -- "$stage"
if $update_enabled; then
    updater_unit_dir=$(dirname -- "$unit_target")
    config_dir=/etc/codexrm
    updater_timer=codexrm-server-update.timer
    has_timer=false
    if [[ -f "$updater_unit_dir/$updater_timer" ]]; then has_timer=true; fi
    do_updater_install() {
        install -m 0755 "$updater" "$root/bin/.codexrm-server-updater.next" &&
            mv -fT -- "$root/bin/.codexrm-server-updater.next" "$root/bin/codexrm-server-updater" &&
            install -d -m 0755 "$config_dir" &&
            { [[ -e "$config_dir/server-update.env" ]] ||
              install -m 0644 "$package_dir/deploy/codexrm-server-update.env.example" "$config_dir/server-update.env"; } &&
            install -m 0644 "$package_dir/deploy/codexrm-server-update.service" "$updater_unit_dir/codexrm-server-update.service" &&
            install -m 0644 "$package_dir/deploy/$updater_timer" "$updater_unit_dir/$updater_timer" &&
            systemctl daemon-reload &&
            { $has_timer || systemctl enable --now "$updater_timer"; }
    }
    if ! do_updater_install; then
        printf 'Relay is installed, but the Server update scheduler setup failed.\n' >&2
        exit 1
    fi
    printf 'Server update scheduler installed; configure %s/server-update.env\n' "$config_dir"
fi
