#!/usr/bin/env bash
# Exercise the installer against temporary files and an isolated systemctl double.
set -euo pipefail
scripts=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
fixture=$(mktemp -d "${TMPDIR:-/tmp}/codexrm-install-test.XXXXXXXX")
[[ -n "$fixture" && "$fixture" == "${TMPDIR:-/tmp}"/codexrm-install-test.* ]]
trap 'rm -rf -- "$fixture"' EXIT
sed -e 's|^root=/opt/codexrm$|root="$TEST_CASE/root"|' \
    -e 's|^unit_target=.*|unit_target="$TEST_CASE/systemd/$service"|' \
    -e 's|config_dir=/etc/codexrm|config_dir="$TEST_CASE/conf"|' \
    -e 's|for attempt in {1..20};|for attempt in {1..2};|' \
    "$scripts/install-server.sh" > "$fixture/installer.sh"

id() { echo 0; }
getent() { return 0; }
flock() { return 0; }
sleep() { return 0; }
chmod() { return 0; }
install() {
    if [[ "$1" == -d ]]; then
        mkdir -p -- "${@: -1}"
        if [[ "${@: -2:1}" != 0[0-7][0-7][0-7] ]]; then mkdir -p -- "${@: -2:1}"; fi
    else
        cp -- "${@: -2}"
    fi
}
systemctl() {
    printf '%s\n' "$1" >> "$TEST_CASE/systemctl.log"
    case "$1" in
        is-active) [[ -f "$TEST_CASE/active" ]] ;;
        is-enabled) [[ -f "$TEST_CASE/enabled" ]] ;;
        restart)
            if [[ "$TEST_FAILURE" == restart && $(cat "$TEST_CASE/root/bin/codexrm-server") == new ]]; then return 1; fi
            : > "$TEST_CASE/active" ;;
        stop) rm -f -- "$TEST_CASE/active" ;;
        enable)
            if [[ "${2-}" == --now ]]; then : > "$TEST_CASE/timer-enabled"; else : > "$TEST_CASE/enabled"; fi ;;
        disable) rm -f -- "$TEST_CASE/enabled" ;;
    esac
}
curl() {
    if [[ "$TEST_FAILURE" == health && $(cat "$TEST_CASE/root/bin/codexrm-server") == new ]]; then return 22; fi
    if [[ "$TEST_FAILURE" == managed* ]]; then
        printf '%s\n' '{"ok":true,"service":"codexrm","version":"0.6.0","serverVersionCode":1,"serverVersionName":"0.1.0"}' 200
    else
        printf '%s\n' '{"ok":true,"service":"codexrm","version":"test"}' 200
    fi
}
export -f id getent flock install chmod sleep systemctl curl
export TEST_CASE TEST_FAILURE

for scenario in success health restart fresh managed managed-disabled; do
    TEST_CASE="$fixture/$scenario"
    TEST_FAILURE=$scenario
    mkdir -p "$TEST_CASE/root/bin" "$TEST_CASE/systemd" "$TEST_CASE/package/web" "$TEST_CASE/package/deploy"
    printf new > "$TEST_CASE/package/codexrm-server"
    printf new > "$TEST_CASE/package/deploy/codexrm.service"
    printf new > "$TEST_CASE/package/web/index.html"
    cp -- "$fixture/installer.sh" "$TEST_CASE/package/install-server.sh"
    if [[ "$scenario" == managed* ]]; then
        deploy_dir=$(cd -- "$scripts/../../deploy" && pwd)
        cp -- "$deploy_dir"/codexrm-server-update.* "$TEST_CASE/package/deploy/"
        printf 'updater' > "$TEST_CASE/package/codexrm-server-updater"
        if [[ "$scenario" == managed-disabled ]]; then
            : > "$TEST_CASE/systemd/codexrm-server-update.timer"
            mkdir -p "$TEST_CASE/conf"
            echo 'CODEXRM_SERVER_AUTO_UPDATE=false' > "$TEST_CASE/conf/server-update.env"
        fi
    fi
    if [[ "$scenario" == fresh ]]; then
        TEST_FAILURE=health
    else
        mkdir -p "$TEST_CASE/root/web"
        printf old > "$TEST_CASE/root/bin/codexrm-server"
        printf old > "$TEST_CASE/systemd/codexrm.service"
        printf old > "$TEST_CASE/root/web/index.html"
        printf existing > "$TEST_CASE/root/server.env"
        : > "$TEST_CASE/active"
        : > "$TEST_CASE/enabled"
    fi
    if bash "$TEST_CASE/package/install-server.sh"; then
        [[ "$scenario" == success || "$scenario" == managed* ]]
        expected=new
    else
        [[ "$scenario" != success && "$scenario" != managed* ]]
        expected=old
    fi
    if [[ "$scenario" == fresh ]]; then
        [[ ! -e "$TEST_CASE/active" && ! -e "$TEST_CASE/enabled" ]]
        [[ ! -e "$TEST_CASE/root/bin/codexrm-server" && ! -e "$TEST_CASE/root/web" && ! -e "$TEST_CASE/systemd/codexrm.service" ]]
    else
        [[ $(cat "$TEST_CASE/root/bin/codexrm-server") == "$expected" ]]
        [[ $(cat "$TEST_CASE/systemd/codexrm.service") == "$expected" ]]
        [[ $(cat "$TEST_CASE/root/web/index.html") == "$expected" ]]
        [[ $(cat "$TEST_CASE/root/server.env") == existing ]]
        [[ -f "$TEST_CASE/active" && -f "$TEST_CASE/enabled" ]]
    fi
    if [[ "$scenario" == managed* ]]; then
        [[ $(cat "$TEST_CASE/root/bin/codexrm-server-updater") == updater ]]
        [[ -f "$TEST_CASE/systemd/codexrm-server-update.service" && -f "$TEST_CASE/systemd/codexrm-server-update.timer" ]]
        if [[ "$scenario" == managed-disabled ]]; then
            [[ ! -e "$TEST_CASE/timer-enabled" ]]
            grep -xq 'CODEXRM_SERVER_AUTO_UPDATE=false' "$TEST_CASE/conf/server-update.env"
        else
            [[ -f "$TEST_CASE/timer-enabled" ]]
            grep -xq 'CODEXRM_SERVER_AUTO_UPDATE=true' "$TEST_CASE/conf/server-update.env"
        fi
    fi
    [[ -z $(find "$TEST_CASE/root" -name '.install.*' ! -name '.install.lock' -print -quit) ]]
    printf 'PASS: %s\n' "$scenario"
done
