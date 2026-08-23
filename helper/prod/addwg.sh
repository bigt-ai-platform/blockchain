#!/usr/bin/env bash
# addwg.sh — join this host to the prod test WireGuard mesh (10.8.0.x).
#
# Usage (root):
#   sudo ./addwg.sh                       interactive: prompts for hub pubkey+endpoint
#   sudo ./addwg.sh 10.8.0.4 <HUB_PUBKEY> <HUB_ENDPOINT>
#   sudo HUB_SSH=root@s2001.bigt.ai ./addwg.sh    auto-fetch hub data + register via SSH
#   ./addwg.sh status                     verify tunnel + mesh reachability (no root needed)
#
# Env: WG_IFACE (default wg0), HUB_SSH (user@hubhost — fetches pubkey/port and
#      registers this peer remotely), HUB_KEY (ssh identity),
#      HUB_WG_IFACE (default wg0), KEEPALIVE (default 25).
set -euo pipefail

WG_IFACE="${WG_IFACE:-wg0}"
WG_CONF="/etc/wireguard/${WG_IFACE}.conf"
WG_DIR="/etc/wireguard"
KNOWN_PEERS="1 2 3"
KEEPALIVE="${KEEPALIVE:-25}"
HUB_SSH="${HUB_SSH:-}"
# Under sudo $HOME is /root; resolve the invoking user's home so their ssh key is found.
WG_REAL_HOME="${HOME}"
if [ -n "${SUDO_USER:-}" ]; then
    WG_SUDO_HOME="$(getent passwd "$SUDO_USER" | cut -d: -f6)"
    [ -n "$WG_SUDO_HOME" ] && WG_REAL_HOME="$WG_SUDO_HOME"
fi
HUB_KEY="${HUB_KEY:-${WG_REAL_HOME}/.ssh/oraclevpc.key}"
HUB_WG_IFACE="${HUB_WG_IFACE:-wg0}"
SSH_OPTS=(-o BatchMode=yes -o ConnectTimeout=10 -o StrictHostKeyChecking=accept-new)
if [ -f "$HUB_KEY" ]; then SSH_OPTS+=(-i "$HUB_KEY"); fi

die() { echo "addwg: $*" >&2; exit 1; }
log() { echo "[addwg] $*"; }

next_free_ip() {
    local used i candidate base="${WG_NEXT_IP_BASE:-10.8.0}"
    used=" ${KNOWN_PEERS} "
    if [ -f "$WG_CONF" ]; then
        used="${used}${base}. "
    fi
    for i in $(seq 4 250); do
        candidate="${base}.${i}"
        case "$used" in *" ${candidate} "*) continue ;; esac
        echo "$candidate"
        return 0
    done
    die "no free mesh ip in ${base}.4-.250"
}

cmd_status() {
    command -v wg >/dev/null || die "wireguard tools not installed"
    wg show || true
    local hp
    for hp in 10.8.0.1 10.8.0.2 10.8.0.3; do
        if ping -c1 -W2 "$hp" >/dev/null 2>&1; then
            log "mesh: ${hp} reachable"
        else
            log "mesh: ${hp} unreachable"
        fi
    done
}

hub_ssh() {
    ssh "${SSH_OPTS[@]}" "$HUB_SSH" "$*"
}

hub_fetch() { # → sets HUB_PUB, HUB_ENDPOINT
    [ -n "$HUB_SSH" ] || return 1
    local port host
    log "fetching hub data from ${HUB_SSH}"
    HUB_PUB="$(hub_ssh "wg show ${HUB_WG_IFACE} public-key")"
    port="$(hub_ssh "wg show ${HUB_WG_IFACE} listen-port")"
    host="${HUB_SSH#*@}"
    if getent hosts "$host" >/dev/null 2>&1; then
        HUB_ENDPOINT="${host}:${port}"
    else
        HUB_ENDPOINT="$(hub_ssh "echo \$(ip -4 route get 1.1.1.1 | awk '{for(i=1;i<=NF;i++) if(\$i==\"src\"){print \$(i+1); exit}}')"):${port}"
    fi
}

hub_register() { # $1=peer-pub $2=mesh-ip
    log "registering peer on hub ${HUB_SSH}"
    hub_ssh "wg set ${HUB_WG_IFACE} peer '$1' allowed-ips '$2/32' && wg-quick save ${HUB_WG_IFACE}" >/dev/null
}

main() {
    if [ "${1:-}" = "status" ]; then cmd_status; return 0; fi

    if [ "$(id -u)" != "0" ]; then die "run with sudo"; fi
    local mesh_ip="${1:-}" hub_pub="${2:-}" hub_ep="${3:-}"

    if ! command -v wg >/dev/null 2>&1; then
        log "installing wireguard"
        export DEBIAN_FRONTEND=noninteractive
        # A broken third-party source must not abort the install; existing
        # package indexes are enough to fetch wireguard.
        apt-get update -qq || log "apt update reported errors; using cached indexes"
        apt-get install -y -qq wireguard
    fi

    if [ -f "$WG_CONF" ] && [ -z "${FORCE:-}" ]; then
        die "${WG_CONF} exists (set FORCE=1 to overwrite)"
    fi

    if [ -n "$HUB_SSH" ]; then
        hub_fetch || die "cannot reach hub via ssh (${HUB_SSH})"
        hub_pub="${2:-${HUB_PUB}}"
        hub_ep="${3:-${HUB_ENDPOINT}}"
    fi

    if [ -z "$mesh_ip" ]; then mesh_ip="$(next_free_ip)"; fi
    if [ -z "$hub_pub" ]; then read -r -p "hub public key (s2001): " hub_pub; fi
    if [ -z "$hub_ep" ]; then read -r -p "hub endpoint host:port (s2001 public address): " hub_ep; fi
    [ -n "$hub_pub" ] && [ -n "$hub_ep" ] || die "hub pubkey and endpoint required"

    umask 077
    mkdir -p "$WG_DIR"
    if [ ! -f "${WG_DIR}/privatekey" ]; then
        wg genkey > "${WG_DIR}/privatekey"
    fi
    wg pubkey < "${WG_DIR}/privatekey" > "${WG_DIR}/publickey"

    cat > "$WG_CONF" <<EOF
[Interface]
Address = ${mesh_ip}/24
PrivateKey = $(cat "${WG_DIR}/privatekey")

[Peer]
# s2001 hub
PublicKey = ${hub_pub}
Endpoint = ${hub_ep}
AllowedIPs = 10.8.0.0/24
PersistentKeepalive = ${KEEPALIVE}
EOF
    chmod 600 "$WG_CONF"

    local mypub
    mypub="$(cat "${WG_DIR}/publickey")"

    log "wrote ${WG_CONF} (this host: ${mesh_ip}, hub ${hub_ep})"

    if [ -n "$HUB_SSH" ]; then
        hub_register "$mypub" "$mesh_ip"
    else
        echo
        echo "Next step — on the HUB (s2001), register this peer:"
        echo "  sudo wg set wg0 peer ${mypub} allowed-ips ${mesh_ip}/32"
        echo "  sudo wg-quick save wg0   # persist in /etc/wireguard/wg0.conf"
        echo
        read -r -p "Press Enter AFTER the hub step to bring up ${WG_IFACE}..." _
    fi

    wg-quick up "$WG_IFACE"
    sleep 2
    wg show
    cmd_status
    log "done. Then: NODE_HOST=${mesh_ip} helper/prod/addnode.sh add"
}

main "$@"
