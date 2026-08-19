#!/usr/bin/env bash
# prodtest.sh — end-to-end "from the beginning" test of the deployed PoS
# production network across the WireGuard mesh hosts.
#
# Topology (see helper/prod/validators/common.env + node-<i>/validator.env):
#   s2001  10.8.0.1  -> node-0  server :8081  db layer0    postgres :5432
#                       node-1  server :8082  db layer0_1  postgres :5432
#   cui    10.8.0.2  -> node-2  server :8083  db layer0_2  postgres :5433
#   (node-3 / aliyun 10.8.0.3 is EXCLUDED — its virtual clock cannot hold NTP.)
#
# Reachability from the driver box:
#   cui    = 192.168.178.53  (LAN, ssh directly)
#   s2001  = 10.8.0.1        (WireGuard mesh; ssh via ProxyJump through cui)
#
# Flow (production phased bootstrap, see helper/prod/cutover-runbook.md):
#   preflight -> [sync scripts+images] -> [reset chain state] ->
#   server (all) -> stake (all, active set converges) -> mcmc (all) ->
#   wait first beacon -> [soak N epochs] -> verify (cross-node acceptance).
#
# The mcmc beacon producers MUST NOT start until every validator is staked and
# active, or the later stake deposits land on a moving head and get reorged out
# (the 4-node prodsim regression). This script enforces that ordering.
#
# The driver box is NOT on the 10.8.0.x mesh, so every API check runs ON a mesh
# host (curl to localhost:<serverPort>), never from the driver locally.
#
# Usage: ./prodtest.sh [options]
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
VALIDATORS_DIR="$SCRIPT_DIR/validators"

# ---- Config (override via env) ---------------------------------------------
SSH_USER="${SSH_USER:-root}"
S2001_HOST="${S2001_HOST:-10.8.0.1}"   # node-0 + node-1 (mesh; reached via jump)
CUI_HOST="${CUI_HOST:-192.168.178.53}"  # node-2 (LAN, ssh directly)
# Jump host to reach S2001_HOST from the driver box. Set JUMP_HOST="" when the
# script runs on a mesh host (no proxy needed).
JUMP_HOST="${JUMP_HOST-root@192.168.178.53}"
# Host used for all HTTP checks (must reach the whole 10.8.0.x mesh).
CHECK_HOST="${CHECK_HOST:-$CUI_HOST}"
REMOTE_DIR="${REMOTE_DIR:-/opt/bigtangle-prod}"
SERVER_IMAGE="${SERVER_IMAGE:-ghcr.io/bigt-ai-platform/layer0-server}"
MCMC_IMAGE="${MCMC_IMAGE:-ghcr.io/bigt-ai-platform/layer0-mcmc}"
IMAGE_TAG="${IMAGE_TAG:-latest}"
SSH_OPTS="${SSH_OPTS:--o BatchMode=yes -o ConnectTimeout=10}"
SEED_HOSTS="${SEED_HOSTS:-10.8.0.1:8081,10.8.0.1:8082,10.8.0.2:8083}"
EXPECTED_VALIDATORS="${EXPECTED_VALIDATORS:-3}"
POS_SLOT_INTERVAL_MS="${POS_SLOT_INTERVAL_MS:-12000}"
POS_SLOTS_PER_EPOCH="${POS_SLOTS_PER_EPOCH:-32}"
SOAK_EPOCHS="${SOAK_EPOCHS:-2}"
DB_USERNAME="${DB_USERNAME:-root}"
DB_PASSWORD="${DB_PASSWORD:-test1234}"
DB_PORT_N0="${DB_PORT_N0:-5432}"; DB_NAME_N0="${DB_NAME_N0:-layer0}"
DB_PORT_N1="${DB_PORT_N1:-5432}"; DB_NAME_N1="${DB_NAME_N1:-layer0_1}"
DB_PORT_N2="${DB_PORT_N2:-5433}"; DB_NAME_N2="${DB_NAME_N2:-layer0_2}"

NODE_HOSTS[0]="$S2001_HOST"
NODE_HOSTS[1]="$S2001_HOST"
NODE_HOSTS[2]="$CUI_HOST"

RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; CYAN='\033[0;36m'; NC='\033[0m'
log()    { echo -e "${GREEN}[OK]${NC} $1"; }
info()   { echo -e "${YELLOW}[INFO]${NC} $1"; }
warn()   { echo -e "${RED}[WARN]${NC} $1"; }
fail()   { echo -e "${RED}[FAIL]${NC} $1"; exit 1; }
header() { echo; echo -e "${CYAN}────── $1 ──────${NC}"; }

usage() {
    cat <<'EOF'
prodtest.sh — run the deployed PoS production network end-to-end ("from the beginning").

  ./prodtest.sh [options]

Options:
  --no-reset       do NOT wipe chain state (containers + databases)
  --no-sync        do NOT sync scripts/images to the hosts
  --verify-only    only run cross-node verification (network must be up)
  --no-soak        skip the epoch soak (still verifies after the first beacon)
  -h, --help       this help

Env:
  SSH_USER              ssh user on the mesh hosts            (default: root)
  S2001_HOST            node-0/node-1 host (mesh)             (default: 10.8.0.1)
  CUI_HOST              node-2 host (LAN, direct ssh)          (default: 192.168.178.53)
  JUMP_HOST             ProxyJump to reach S2001_HOST         (default: root@192.168.178.53; "" = direct)
  CHECK_HOST            host running the API checks           (default: $CUI_HOST)
  REMOTE_DIR            validators scripts dir on each host   (default: /opt/bigtangle-prod)
  SERVER_IMAGE / MCMC_IMAGE  container images                 (default: ghcr.io/bigt-ai-platform/layer0-{server,mcmc})
  IMAGE_TAG             image tag to run                       (default: latest)
  SEED_HOSTS            server mesh "host:port,..."            (default: 10.8.0.1:8081,10.8.0.1:8082,10.8.0.2:8083)
  EXPECTED_VALIDATORS   active validator count                 (default: 3)
  SOAK_EPOCHS           epochs to soak before verify           (default: 2)
  POS_SLOT_INTERVAL_MS / POS_SLOTS_PER_EPOCH                   (default: 12000 / 32)
  DB_USERNAME / DB_PASSWORD   postgres creds on the hosts     (default: root / test1234)
EOF
}

DO_RESET=true; DO_SYNC=true; DO_SOAK=true; VERIFY_ONLY=false
while [[ $# -gt 0 ]]; do
    case "$1" in
        --no-reset)    DO_RESET=false; shift ;;
        --no-sync)     DO_SYNC=false; shift ;;
        --verify-only) VERIFY_ONLY=true; DO_RESET=false; DO_SYNC=false; shift ;;
        --no-soak)     DO_SOAK=false; shift ;;
        -h|--help)     usage; exit 0 ;;
        *) echo "Unknown option: $1"; usage; exit 1 ;;
    esac
done

# ---- Remote helpers ---------------------------------------------------------
ssh_transport() { # $1=host -> prints the ssh command (with jump opts if needed)
    local host="$1"
    if [ "$host" = "$S2001_HOST" ] && [ -n "${JUMP_HOST:-}" ]; then
        echo "ssh $SSH_OPTS -J ${JUMP_HOST} -o StrictHostKeyChecking=accept-new"
    else
        echo "ssh $SSH_OPTS"
    fi
}

scp_transport() { # $1=host -> scp-compatible options (no leading "ssh" token)
    local host="$1"
    if [ "$host" = "$S2001_HOST" ] && [ -n "${JUMP_HOST:-}" ]; then
        echo "-o StrictHostKeyChecking=accept-new -J ${JUMP_HOST} $SSH_OPTS"
    else
        echo "-o StrictHostKeyChecking=accept-new $SSH_OPTS"
    fi
}

remote() { # $1=host  rest=remote command string
    local host="$1"; shift
    # shellcheck disable=SC2086
    $(ssh_transport "$host") "${SSH_USER}@${host}" "$*"
}

seed_host() { echo "${1%%:*}"; }
seed_port() { echo "${1##*:}"; }

# All API calls run on CHECK_HOST (cui) against the seed's MESH address, because
# the nodes bind --server.address=<10.8.0.x> (not localhost) and the driver box
# has no route into the mesh itself.
node_http() { # $1=seed(host:port) $2=path $3=json-body -> prints response body
    remote "$CHECK_HOST" "curl -s -X POST http://${1}${2} -H 'Content-Type: application/json' -d '${3}'"
}

node_up() { # $1=seed -> up/down
    remote "$CHECK_HOST" "curl -sf http://${1}/ >/dev/null 2>&1 && echo up || echo down"
}

chainlength_of() { # $1=seed
    node_http "$1" /getChainNumber '{}' | python3 -c '
import sys, json
try:
    d = json.load(sys.stdin)
except Exception:
    print(0); sys.exit(0)
r = d.get("txReward") or {}
print(r.get("chainLength", 0))'
}

validators_of() { # $1=seed
    node_http "$1" /getValidators '{}' | python3 -c '
import sys, json
try:
    d = json.load(sys.stdin)
except Exception:
    print(0); sys.exit(0)
v = d.get("validators")
if v is None:
    t = d.get("text")
    if isinstance(t, str):
        try:
            t = json.loads(t)
        except Exception:
            pass
    v = t.get("validators") if isinstance(t, dict) else t
if isinstance(v, list):
    print(len(v))
elif isinstance(v, dict):
    print(len(v.get("validators") or []))
else:
    print(0)'
}

finalized_epoch_of() { # $1=seed
    node_http "$1" /getChainNumber '{}' | python3 -c '
import sys, json
try:
    d = json.load(sys.stdin)
except Exception:
    print(0); sys.exit(0)
print(d.get("finalizedEpoch") or 0)'
}

# ---- Preflight / sync / reset ------------------------------------------------
preflight() {
    header "Preflight"
    for h in "$S2001_HOST" "$CUI_HOST"; do
        info "checking ssh ${SSH_USER}@${h}"
        remote "$h" "true" || fail "cannot reach ${SSH_USER}@${h} (WireGuard mesh up? SSH_OPTS=${SSH_OPTS})"
        log "${h} reachable"
    done
    for img in "${SERVER_IMAGE}:${IMAGE_TAG}" "${MCMC_IMAGE}:${IMAGE_TAG}"; do
        if docker image inspect "$img" >/dev/null 2>&1; then
            log "local image ${img} present"
        else
            warn "local image ${img} missing — remote hosts will pull from the registry"
        fi
    done
}

sync_validators() { # $1=host
    local host="$1"
    info "syncing validators scripts -> ${SSH_USER}@${host}:${REMOTE_DIR}"
    remote "$host" "mkdir -p '${REMOTE_DIR}'"
    if command -v rsync >/dev/null 2>&1; then
        # shellcheck disable=SC2086
        rsync -az --delete -e "$(ssh_transport "$host")" "$VALIDATORS_DIR/" "${SSH_USER}@${host}:${REMOTE_DIR}/"
    else
        # shellcheck disable=SC2086
        scp -r $(scp_transport "$host") "$VALIDATORS_DIR/." "${SSH_USER}@${host}:${REMOTE_DIR}/"
    fi
    # Point the remote at the freshly built images. One sed per variable keeps
    # the remote command free of nested quoting.
    remote "$host" "cd '${REMOTE_DIR}' && sed -i 's#^SERVER_IMAGE=.*#SERVER_IMAGE=${SERVER_IMAGE}#' common.env"
    remote "$host" "cd '${REMOTE_DIR}' && sed -i 's#^MCMC_IMAGE=.*#MCMC_IMAGE=${MCMC_IMAGE}#' common.env"
    remote "$host" "cd '${REMOTE_DIR}' && sed -i 's#^IMAGE_TAG=.*#IMAGE_TAG=${IMAGE_TAG}#' common.env"
    log "validators scripts synced to ${host}"
}

sync_images() { # $1=host
    local host="$1"
    for img in "${SERVER_IMAGE}:${IMAGE_TAG}" "${MCMC_IMAGE}:${IMAGE_TAG}"; do
        if docker image inspect "$img" >/dev/null 2>&1; then
            info "loading ${img} -> ${host}"
            # shellcheck disable=SC2086
            docker save "$img" | $(ssh_transport "$host") "${SSH_USER}@${host}" docker load
        else
            warn "skip loading ${img} (not present locally)"
        fi
    done
}

reset_node() { # $1=host $2=node-index $3=db-port $4=db-name
    local host="$1" idx="$2" dbport="$3" dbname="$4"
    info "reset node-${idx} on ${host}: remove containers + drop db ${dbname} (:${dbport})"
    remote "$host" "docker rm -f node-${idx}-server node-${idx}-mcmc >/dev/null 2>&1 || true"
    remote "$host" "PGPASSWORD='${DB_PASSWORD}' psql -h localhost -p ${dbport} -U '${DB_USERNAME}' -d postgres -c 'DROP DATABASE IF EXISTS ${dbname} WITH (FORCE);' >/dev/null 2>&1 || true"
    log "node-${idx} reset"
}

# ---- Waits -------------------------------------------------------------------
wait_servers() {
    header "Waiting for server APIs"
    local seed up
    for seed in $(echo "$SEED_HOSTS" | tr ',' ' '); do
        info "waiting for server ${seed}"
        for i in $(seq 1 100); do
            up=$(node_up "$seed")
            [ "$up" = "up" ] && { log "${seed} ready (${i} x 3s)"; break; }
            [ "$i" -eq 100 ] && fail "${seed} not up after 300s"
            sleep 3
        done
    done
}

wait_genesis() {
    header "Waiting for genesis (chainLength 0)"
    local seed cl
    seed=$(echo "$SEED_HOSTS" | tr ',' ' ' | awk '{print $1}')
    for i in $(seq 1 30); do
        cl=$(chainlength_of "$seed")
        [ "$cl" -eq 0 ] && { log "genesis confirmed on ${seed} (chainLength=0)"; return 0; }
        [ "$i" -eq 30 ] && fail "genesis not confirmed after 90s (chainLength=${cl})"
        sleep 3
    done
}

wait_validators() {
    header "Waiting for active set == ${EXPECTED_VALIDATORS}"
    local seed v ok
    for i in $(seq 1 120); do
        ok=true
        for seed in $(echo "$SEED_HOSTS" | tr ',' ' '); do
            v=$(validators_of "$seed")
            [ "$v" -lt "$EXPECTED_VALIDATORS" ] && { ok=false; break; }
        done
        [ "$ok" = true ] && { log "active set converged to ${EXPECTED_VALIDATORS} on every node"; return 0; }
        [ "$i" -eq 120 ] && fail "active set did not converge to ${EXPECTED_VALIDATORS} (last node: ${v})"
        sleep 3
    done
}

wait_beacon() {
    header "Waiting for the first confirmed beacon (chainLength > 0)"
    local seed cl maxcl
    for i in $(seq 1 100); do
        maxcl=0
        for seed in $(echo "$SEED_HOSTS" | tr ',' ' '); do
            cl=$(chainlength_of "$seed")
            [ "$cl" -gt "$maxcl" ] && maxcl=$cl
        done
        [ "$maxcl" -gt 0 ] && { log "beacon chain confirmed, chainLength=${maxcl}"; return 0; }
        [ "$i" -eq 100 ] && fail "no confirmed beacon after 300s"
        sleep 3
    done
}

# ---- Phases (production ordering) ---------------------------------------------
phase_server() {
    header "Phase 1/4 server — start layer0-server on every node (no beacons)"
    for idx in 0 1 2; do
        info "node-${idx} (${NODE_HOSTS[$idx]}): setup.sh server"
        remote "${NODE_HOSTS[$idx]}" "cd '${REMOTE_DIR}' && ./node-${idx}/setup.sh server"
        log "node-${idx} server up"
    done
    wait_servers
    wait_genesis
}

phase_stake() {
    header "Phase 2/4 stake — fund + stake + activate every validator (own node)"
    for idx in 0 1 2; do
        info "node-${idx} (${NODE_HOSTS[$idx]}): setup.sh stake"
        remote "${NODE_HOSTS[$idx]}" "cd '${REMOTE_DIR}' && ./node-${idx}/setup.sh stake"
        log "node-${idx} staked + activated"
    done
    wait_validators
}

phase_mcmc() {
    header "Phase 3/4 mcmc — start beacon producers (full active set is staked)"
    for idx in 0 1 2; do
        info "node-${idx} (${NODE_HOSTS[$idx]}): setup.sh mcmc"
        remote "${NODE_HOSTS[$idx]}" "cd '${REMOTE_DIR}' && ./node-${idx}/setup.sh mcmc"
        log "node-${idx} mcmc started"
    done
    wait_beacon
}

soak() {
    [ "${DO_SOAK}" = false ] && return 0
    [ "${SOAK_EPOCHS}" -le 0 ] && return 0
    local secs=$(( SOAK_EPOCHS * POS_SLOTS_PER_EPOCH * POS_SLOT_INTERVAL_MS / 1000 ))
    header "Phase 4/4 soak ${SOAK_EPOCHS} epoch(s) ~ ${secs}s"
    sleep "$secs"
}

# ---- Verification --------------------------------------------------------------
verify() {
    header "Verify — cross-node acceptance (${EXPECTED_VALIDATORS} validators)"
    local seed v cl maxcl=0 mincl=999999999
    for seed in $(echo "$SEED_HOSTS" | tr ',' ' '); do
        v=$(validators_of "$seed"); cl=$(chainlength_of "$seed")
        info "  ${seed}: validators=${v} chainlength=${cl}"
        [ "$v" -lt "$EXPECTED_VALIDATORS" ] && fail "${seed}: ${v}/${EXPECTED_VALIDATORS} validators"
        [ "$cl" -gt "$maxcl" ] && maxcl=$cl
        [ "$cl" -lt "$mincl" ] && mincl=$cl
    done
    [ "$maxcl" -eq 0 ] && fail "no node has confirmed a beacon"
    [ $((maxcl - mincl)) -gt "$POS_SLOTS_PER_EPOCH" ] && fail "confirmed chainlength spread ${mincl}..${maxcl} > ${POS_SLOTS_PER_EPOCH} epoch"
    log "OK: ${EXPECTED_VALIDATORS} validators active everywhere; confirmed chainlength ${mincl}..${maxcl}"
}

finality_check() {
    header "Finality check (Casper justified/finalized checkpoints)"
    local seed e fe=0
    for seed in $(echo "$SEED_HOSTS" | tr ',' ' '); do
        e=$(finalized_epoch_of "$seed")
        info "  ${seed}: finalizedEpoch=${e}"
        [ "$e" -gt "$fe" ] && fe=$e
    done
    if [ "$fe" -gt 0 ]; then
        log "finality advancing: finalized epoch ${fe}"
    else
        warn "no finalized checkpoint yet (needs a full epoch)"
    fi
}

# ---- Main -----------------------------------------------------------------------
main() {
    preflight
    if [ "$DO_SYNC" = true ]; then
        sync_validators "$S2001_HOST"
        sync_validators "$CUI_HOST"
        sync_images "$S2001_HOST"
        sync_images "$CUI_HOST"
    fi
    if [ "$VERIFY_ONLY" = true ]; then
        verify
        finality_check
        exit 0
    fi
    if [ "$DO_RESET" = true ]; then
        header "Reset — wipe chain state (from the beginning)"
        reset_node "$S2001_HOST" 0 "$DB_PORT_N0" "$DB_NAME_N0"
        reset_node "$S2001_HOST" 1 "$DB_PORT_N1" "$DB_NAME_N1"
        reset_node "$CUI_HOST"   2 "$DB_PORT_N2" "$DB_NAME_N2"
    fi
    phase_server
    phase_stake
    phase_mcmc
    soak
    verify
    finality_check
    echo
    log "PRODTEST: SUCCESS"
}

main