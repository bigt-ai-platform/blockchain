#!/usr/bin/env bash
# addnode.sh — add / leave / rejoin a validator node on the prod test system.
#
# Usage:
#   addnode.sh add    [INDEX] [HOST]   provision + start node (keys, DB, container,
#                                      Kafka via s2001) then stake + activate
#   addnode.sh leave  INDEX [--no-exit] signed validator exit (BLOCKTYPE_EXIT),
#                                      stop container, drop from seeds
#   addnode.sh rejoin INDEX            re-add to seeds, restart, re-stake/activate
#   addnode.sh status                  mesh overview (validators/chainLength per seed)
#   addnode.sh verify [INDEX]          cross-node acceptance (validator_common verify)
#
# add/rejoin auto-start the WireGuard VPN (wg-quick up) when NODE_HOST is on
# the 10.8.0.x mesh and the tunnel is down; run with sudo, or provision the
# tunnel once via helper/prod/addwg.sh.
#
# Env overrides: WG_IFACE (default wg0), NODE_HOST, DB_PORT,
#                KAFKA_BOOTSTRAP (default 10.8.0.1:9092 = s2001),
#                SERVER_JAR, TOOL_IMAGE.
# A node added by this script persists KAFKA_BOOTSTRAP in its validator.env and its
# server starts with --server.runKafkaStream=true --kafka.bootstrapServers=<s2001>.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
VALDIR="${ROOT}/helper/prod/validators"
COMMON_ENV="${VALDIR}/common.env"
COMMON_SH="${VALDIR}/validator_common.sh"

# Caller-provided overrides must survive the common.env source below.
declare -A _CALLER_OV=()
for _v in DB_PORT NODE_HOST KAFKA_BOOTSTRAP SERVER_JAR TOOL_IMAGE PG_CONTAINER; do
    if [ -n "${!_v:-}" ]; then _CALLER_OV["$_v"]="${!_v}"; fi
done

# shellcheck disable=SC1090
source "${COMMON_ENV}"
for _v in "${!_CALLER_OV[@]}"; do
    export "$_v=${_CALLER_OV[$_v]}"
done
unset _v _CALLER_OV

KAFKA_DEFAULT="10.8.0.1:9092"
WG_IFACE="${WG_IFACE:-wg0}"
KAFKA_BOOTSTRAP="${KAFKA_BOOTSTRAP:-${KAFKA_DEFAULT}}"
SERVER_JAR="${SERVER_JAR:-$(ls -t "${ROOT}"/bigtangle-servercore/target/bigtangle-servercore-*-exec.jar 2>/dev/null | head -1 || true)}"
TOOL_IMAGE="${TOOL_IMAGE:-${SERVER_IMAGE}:${IMAGE_TAG}}"
API_BASE=""
NODE_DIR=""
NODE_HOST="${NODE_HOST:-}"

die() { echo "addnode: $*" >&2; exit 1; }
log() { echo "[addnode] $*"; }

usage() { sed -n '2,19p' "$0" | sed 's/^# \{0,1\}//'; }

next_index() {
    local max=-1 d i
    for d in "${VALDIR}"/node-*; do
        if [ -d "$d" ]; then
            i="${d##*/node-}"
            if [[ "$i" =~ ^[0-9]+$ ]] && [ "$i" -gt "$max" ]; then max="$i"; fi
        fi
    done
    echo $((max + 1))
}

detect_host() {
    ip -4 route get 1.1.1.1 2>/dev/null | awk '{for(i=1;i<=NF;i++) if($i=="src"){print $(i+1); exit}}'
}

vpn_start() { # bring up the WireGuard tunnel when NODE_HOST lives on the mesh
    case "${NODE_HOST}" in
        10.8.0.*) ;;
        *) log "vpn: ${NODE_HOST} not on the 10.8.0.x mesh; skipping"; return 0 ;;
    esac
    command -v wg >/dev/null 2>&1 || die "wireguard tools missing (sudo helper/prod/addwg.sh)"
    local conf="/etc/wireguard/${WG_IFACE}.conf"
    if ! ip link show "$WG_IFACE" >/dev/null 2>&1; then
        [ -f "$conf" ] || die "no ${conf}: join the mesh first (sudo helper/prod/addwg.sh)"
        log "vpn start: bringing up ${WG_IFACE}"
        local up=(wg-quick)
        [ "$(id -u)" = "0" ] || up=(sudo -n wg-quick)
        "${up[@]}" up "$WG_IFACE" \
            || die "wg-quick up ${WG_IFACE} failed (needs root: run $0 with sudo, or 'sudo wg-quick up ${WG_IFACE}')"
        sleep 2
    fi
    if ping -c1 -W2 10.8.0.1 >/dev/null 2>&1; then
        log "vpn: ${WG_IFACE} up, hub 10.8.0.1 reachable"
    else
        die "vpn: ${WG_IFACE} up but hub 10.8.0.1 unreachable (sudo helper/prod/addwg.sh status)"
    fi
}

tool_cp() {
    if [ -z "${SERVER_JAR}" ]; then return 1; fi
    local dir="${TMPDIR:-/tmp}/bt-addnode-cp"
    local stamp="${dir}/.stamp"
    if [ ! -f "$stamp" ] || [ "${SERVER_JAR}" -nt "$stamp" ]; then
        rm -rf "$dir"
        mkdir -p "$dir"
        unzip -o -q "${SERVER_JAR}" 'BOOT-INF/*' -d "$dir"
        touch "$stamp"
    fi
    printf '%s' "${dir}/BOOT-INF/classes:${dir}/BOOT-INF/lib/*"
}

keygen() {
    local cp
    if cp="$(tool_cp)"; then
        java -cp "$cp" net.bigtangle.tools.ValidatorKeyTool generate
    else
        docker run --rm --network none --entrypoint java "${TOOL_IMAGE}" \
            -cp /app/app.jar net.bigtangle.tools.ValidatorKeyTool generate
    fi
}

sign_exit() { # $1=key-hex $2=nonce
    local cp
    cp="$(tool_cp)" || die "exit signing needs SERVER_JAR (exec jar); none found"
    java -cp "$cp" "${VALDIR}/SignExit.java" "$1" "$2"
}

seeds_line() { # $1=varname → value without quotes
    grep -E "^${1}=" "${COMMON_ENV}" | head -1 | cut -d= -f2- | tr -d '"'
}

seeds_has_host() {
    seeds_line SEED_HOSTS | tr ',' '\n' | grep -q "^${1}:" || return 1
    return 0
}

seeds_add() { # $1=host $2=api-port $3=gossip-port
    if ! seeds_has_host "$1"; then
        sed -i "s|^SEED_HOSTS=.*|SEED_HOSTS=\"$(seeds_line SEED_HOSTS),${1}:${2}\"|" "${COMMON_ENV}"
        sed -i "s|^GOSSIP_SEEDS=.*|GOSSIP_SEEDS=\"$(seeds_line GOSSIP_SEEDS),${1}:${3}\"|" "${COMMON_ENV}"
        log "common.env: added ${1}:${2} to SEED_HOSTS, :${3} to GOSSIP_SEEDS"
    fi
}

filter_list() { # $1=list $2=host → entries not starting host:
    local out="" e
    local IFS=','
    for e in $1; do
        if [ -n "$e" ] && [[ "$e" != "${2}:"* ]]; then
            out="${out:+${out},}${e}"
        fi
    done
    printf '%s' "$out"
}

seeds_remove() { # $1=host
    local s g ns ng
    s="$(seeds_line SEED_HOSTS)"
    g="$(seeds_line GOSSIP_SEEDS)"
    ns="$(filter_list "$s" "$1")"
    ng="$(filter_list "$g" "$1")"
    if [ "$ns" != "$s" ] || [ "$ng" != "$g" ]; then
        sed -i "s|^SEED_HOSTS=.*|SEED_HOSTS=\"${ns}\"|" "${COMMON_ENV}"
        sed -i "s|^GOSSIP_SEEDS=.*|GOSSIP_SEEDS=\"${ng}\"|" "${COMMON_ENV}"
        log "common.env: removed ${1}:* from seeds"
    fi
}

load_node() { # $1=index
    NODE_INDEX="$1"
    NODE_DIR="${VALDIR}/node-${NODE_INDEX}"
    if [ ! -f "${NODE_DIR}/validator.env" ]; then
        die "node-${NODE_INDEX} not provisioned (run: $0 add ${NODE_INDEX})"
    fi
    # shellcheck disable=SC1090
    source "${COMMON_ENV}"
    # shellcheck disable=SC1090
    source "${NODE_DIR}/validator.env"
    # shellcheck disable=SC1090
    source "${COMMON_SH}"
}

validators_count_of() { # $1=base-url
    curl -sf -m 5 -X POST "${1}/getValidators" -H 'Content-Type: application/json' -d '{}' 2>/dev/null \
        | python3 -c 'import sys,json; d=json.load(sys.stdin); v=d.get("text") or d.get("validators"); import json as j; v=j.loads(v) if isinstance(v,str) else v; v=(v or {}).get("validators") if isinstance(v,dict) else v; print(len(v or []))' 2>/dev/null \
        || echo "-"
}

chainlength_of() { # $1=base-url
    curl -sf -m 5 -X POST "${1}/getChainNumber" -H 'Content-Type: application/json' -d '{}' 2>/dev/null \
        | python3 -c 'import sys,json; d=json.load(sys.stdin); r=d.get("txReward"); import json as j; r=j.loads(r) if isinstance(r,str) else r; print((r or {}).get("chainLength",0))' 2>/dev/null \
        || echo ""
}

validator_state_of() { # $1=base-url $2=pubkey-hex → active|exiting|gone|unreachable
    curl -sf -m 5 -X POST "${1}/getValidators" -H 'Content-Type: application/json' -d '{}' 2>/dev/null \
        | PUBKEY="$2" python3 -c '
import sys, json, base64, os
want = os.environ["PUBKEY"]
try:
    d = json.load(sys.stdin)
except Exception:
    print("unreachable"); sys.exit(0)
v = d.get("text") or d.get("validators")
v = json.loads(v) if isinstance(v, str) else v
vs = (v or {}).get("validators") if isinstance(v, dict) else v
state = "gone"
for s in vs or []:
    try:
        pk = base64.b64decode((s or {}).get("pubkey") or "").hex()
    except Exception:
        pk = ""
    if pk == want:
        state = "exiting" if (s or {}).get("exiting") else "active"
print(state)
'
}

wait_synced() { # block until own chainLength is within one epoch of the mesh head
    local best=0 cl hp n epoch="${POS_SLOTS_PER_EPOCH:-32}"
    for hp in $(echo "${SEED_HOSTS}" | tr ',' ' '); do
        cl="$(chainlength_of "http://${hp}")"
        if [ "${cl:-0}" -gt "$best" ] 2>/dev/null; then best="$cl"; fi
    done
    log "waiting to sync to mesh head (~${best}); this pulls the full DAG first"
    for n in $(seq 1 120); do
        cl="$(chainlength_of "${API_BASE}")"
        if [ "${cl:-0}" -ge $((best - epoch)) ] 2>/dev/null; then
            log "synced: chainLength=${cl} (head ${best})"
            return 0
        fi
        if [ $((n % 10)) -eq 0 ]; then log "chainLength=${cl:-0}/${best}..."; fi
        sleep 5
    done
    die "not synced after timeout (own=${cl:-?}, head=${best}); stake would land on a stale head"
}

reachable_seed_or_own() { # → echoes API base (own if up, else first reachable seed)
    if curl -sf -m 5 "${API_BASE}/" >/dev/null 2>&1; then
        printf '%s' "${API_BASE}"
        return 0
    fi
    local hp
    for hp in $(echo "${SEED_HOSTS}" | tr ',' ' '); do
        if curl -sf -m 5 "http://${hp}/" >/dev/null 2>&1; then
            printf '%s' "http://${hp}"
            return 0
        fi
    done
    return 1
}

cmd_add() {
    local idx="${1:-$(next_index)}"
    local host="${2:-${NODE_HOST}}"
    if [ -z "$host" ]; then host="$(detect_host)"; fi
    if [ -z "$host" ]; then die "cannot detect local IP; pass HOST or set NODE_HOST"; fi
    if [ -e "${VALDIR}/node-${idx}" ]; then
        die "node-${idx} already exists (rejoin instead, or pick another index)"
    fi
    command -v docker >/dev/null || die "docker required"
    docker image inspect "${TOOL_IMAGE}" >/dev/null 2>&1 \
        || die "image missing: ${TOOL_IMAGE} (build with helper/deploy.sh)"

    vpn_start

    log "provisioning node-${idx} on ${host} (kafka=${KAFKA_BOOTSTRAP})"
    mkdir -p "${VALDIR}/node-${idx}"
    local out key pub hash addr db_name
    out="$(keygen)"
    key="$(echo "$out" | grep '^POS_VALIDATOR_KEY=' | cut -d= -f2-)"
    pub="$(echo "$out" | grep '^VALIDATOR_PUBKEY=' | cut -d= -f2-)"
    hash="$(echo "$out" | grep '^PUBKEY_HASH=' | cut -d= -f2-)"
    addr="$(echo "$out" | grep '^ADDRESS=' | cut -d= -f2-)"
    if [ -z "$key" ] || [ -z "$pub" ]; then
        rm -rf "${VALDIR}/node-${idx}"
        die "ValidatorKeyTool produced no key: ${out}"
    fi

    db_name="layer0_${idx}"
    if [ "$idx" = "0" ]; then db_name="layer0"; fi
    {
        echo "# Node ${idx} credentials — KEEP SECRET (gitignored). Generated by addnode.sh."
        echo "NODE_INDEX=${idx}"
        echo "NODE_HOST=${host}"
        echo "POS_VALIDATOR_KEY=${key}"
        echo "VALIDATOR_PUBKEY=${pub}"
        echo "PUBKEY_HASH=${hash}"
        echo "ADDRESS=${addr}"
        echo "SERVER_PORT=$((8081 + idx))"
        echo "SERVER_PEER_UDP=$((30307 + 4 * idx))"
        echo "SERVER_PEER_TCP=$((30308 + 4 * idx))"
        echo "SERVER_GOSSIP=$((9095 + 4 * idx))"
        echo "MCMC_PORT=$((8091 + idx))"
        echo "MCMC_PEER_UDP=$((30309 + 4 * idx))"
        echo "MCMC_PEER_TCP=$((30310 + 4 * idx))"
        echo "MCMC_GOSSIP=$((9097 + 4 * idx))"
        echo "DB_NAME=${db_name}"
        echo "KAFKA_BOOTSTRAP=${KAFKA_BOOTSTRAP}"
        if [ -n "${DB_PORT:-}" ]; then echo "DB_PORT=${DB_PORT}"; fi
    } > "${VALDIR}/node-${idx}/validator.env"
    chmod 600 "${VALDIR}/node-${idx}/validator.env"
    cat > "${VALDIR}/node-${idx}/setup.sh" <<EOF
#!/usr/bin/env bash
# Node ${idx} — phased setup: setup.sh <server|stake|verify>. Run the
# phases in order across ALL nodes: server (all) → stake (all) → verify.
set -euo pipefail
cd "\$(dirname "\$0")"

# shellcheck source=../common.env
source ../common.env
# shellcheck source=validator.env
source ./validator.env
# shellcheck source=../validator_common.sh
source ../validator_common.sh

run_phase "\${1:-server}"
EOF
    chmod +x "${VALDIR}/node-${idx}/setup.sh"
    log "wrote node-${idx}/validator.env (address=${addr}) + setup.sh"

    seeds_add "$host" "$((8081 + idx))" "$((9095 + 4 * idx))"

    load_node "$idx"
    log "phase server: create DB + start layer0-server (kafka stream on)"
    run_phase server

    wait_synced

    log "phase stake: fund/stake/activate via own API"
    run_phase stake

    sleep 3
    log "self-check: validators=$(validators_count_of "${API_BASE}") chainLength=$(chainlength_of "${API_BASE}")"
    log "done. Mesh overview: $0 status ; acceptance: $0 verify ${idx}"
    log "NOTE: other hosts must pull this repo so their common.env seeds include ${host}."
}

cmd_leave() {
    local idx="$1"
    shift
    local no_exit=false
    local a
    for a in "$@"; do
        if [ "$a" = "--no-exit" ]; then no_exit=true; fi
    done

    load_node "$idx"

    local target st="" n
    if [ "$no_exit" = false ]; then
        target="$(reachable_seed_or_own)" || die "own API and all seeds unreachable; use: $0 leave ${idx} --no-exit"
        if [ "$target" != "${API_BASE}" ]; then
            log "own API down; submitting via reachable peer ${target}"
        fi
        local nonce out pub sig
        nonce="$(chainlength_of "$target")"
        if [ -z "$nonce" ]; then die "could not read chainLength from ${target}"; fi
        log "requesting validator exit at chainLength=${nonce}"
        out="$(sign_exit "${POS_VALIDATOR_KEY}" "$nonce")"
        pub="$(echo "$out" | grep '^PUBKEY=' | cut -d= -f2-)"
        sig="$(echo "$out" | grep '^SIGNATURE=' | cut -d= -f2-)"
        curl -sf -X POST "${target}/requestValidatorExit" -H 'Content-Type: application/json' \
            -d "{\"pubkey\":\"${pub}\",\"signature\":\"${sig}\"}" >/dev/null \
            || die "requestValidatorExit rejected"
        log "exit submitted; polling getValidators (exiting flag / removal)"
        for n in $(seq 1 60); do
            st="$(validator_state_of "${API_BASE}" "${pub}")"
            if [ "$st" = "exiting" ] || [ "$st" = "gone" ]; then
                log "validator state: ${st}"
                break
            fi
            if [ $((n % 12)) -eq 0 ]; then log "still active... (${n})"; fi
            sleep 5
        done
        if [ "${st:-active}" = "active" ]; then
            log "WARN: still active after timeout; bond releases after the withdrawal delay"
        fi
    fi

    log "stopping container node-${idx}-server"
    docker rm -f "node-${idx}-server" >/dev/null 2>&1 || true
    seeds_remove "${NODE_HOST}"
    log "node-${idx} left the prod test system (stake stays escrowed until withdrawable epoch)."
}

cmd_rejoin() {
    local idx="$1"
    load_node "$idx"
    vpn_start
    local kb
    kb="$(grep -E '^KAFKA_BOOTSTRAP=' "${NODE_DIR}/validator.env" | cut -d= -f2- || true)"
    if [ -z "$kb" ]; then kb="${KAFKA_DEFAULT}"; fi
    KAFKA_BOOTSTRAP="$kb"
    export KAFKA_BOOTSTRAP
    seeds_add "${NODE_HOST}" "${SERVER_PORT}" "${SERVER_GOSSIP}"
    log "rejoining node-${idx} on ${NODE_HOST} (kafka=${KAFKA_BOOTSTRAP})"
    run_phase server
    log "(re)staking — waits for balance >= STAKE_AMOUNT"
    if ! run_phase stake; then
        die "stake incomplete: previous bond may not be withdrawable yet; retry after the epoch passes"
    fi
    log "node-${idx} rejoined."
}

cmd_status() {
    local hp base v cl
    echo "SEED_HOSTS=${SEED_HOSTS}"
    echo "GOSSIP_SEEDS=${GOSSIP_SEEDS}"
    for hp in $(echo "${SEED_HOSTS}" | tr ',' ' '); do
        base="http://${hp}"
        v="$(validators_count_of "$base")"
        cl="$(chainlength_of "$base")"
        printf '  %-24s validators=%-4s chainLength=%s\n' "${hp}" "${v}" "${cl:-?}"
    done
    docker ps --format '{{.Names}}  {{.Status}}' 2>/dev/null | grep -E '^node-[0-9]+-server' || true
}

cmd_verify() {
    load_node "${1:-0}"
    run_phase verify
}

main() {
    if [ $# -lt 1 ]; then usage; exit 1; fi
    local cmd="$1"
    shift
    case "$cmd" in
        add) cmd_add "$@" ;;
        leave)
            if [ $# -lt 1 ]; then die "leave requires INDEX"; fi
            cmd_leave "$@"
            ;;
        rejoin)
            if [ $# -lt 1 ]; then die "rejoin requires INDEX"; fi
            cmd_rejoin "$@"
            ;;
        status) cmd_status ;;
        verify) cmd_verify "$@" ;;
        -h|--help) usage ;;
        *) usage; die "unknown command: ${cmd}" ;;
    esac
}

main "$@"
