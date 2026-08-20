#!/usr/bin/env bash
# Shared functions for per-validator setup.
# Sourced after common.env and node-<i>/validator.env, which must define:
#   NODE_INDEX, NODE_HOST, POS_VALIDATOR_KEY, VALIDATOR_PUBKEY, PUBKEY_HASH
set -euo pipefail

# ---- Derived per-node values ----------------------------------------------
# Each value can be overridden in node-<i>/validator.env (needed when several
# nodes share one host, e.g. node-0 + node-1 on the same server).
SERVER_PORT="${SERVER_PORT:-$((8081 + NODE_INDEX))}"
SERVER_PEER_UDP="${SERVER_PEER_UDP:-$((30307 + NODE_INDEX * 2))}"
SERVER_PEER_TCP="${SERVER_PEER_TCP:-$((30308 + NODE_INDEX * 2))}"
SERVER_GOSSIP="${SERVER_GOSSIP:-$((9095 + NODE_INDEX * 2))}"

if [ "$NODE_INDEX" = "0" ]; then DB_NAME="${DB_NAME:-layer0}"; else DB_NAME="${DB_NAME:-layer0_${NODE_INDEX}}"; fi
API_BASE="http://${NODE_HOST}:${SERVER_PORT}"

# Seed/peer derivation:
#   REQUESTER        = FULL mesh (every validator's server API). A node pulls
#                      missing beacon parents ONLY from its configured requester
#                      (SyncBlockService.requestBlock); a single/self requester
#                      stalls the bootstrap node at the first missing parent and
#                      it confirms zero beacons (the 4-node prodsim regression).
#   POS_GOSSIP_PEERS = full attestation mesh (every validator's server).
CREATETABLE="true"
REQUESTER=""
for _hp in $(echo "${SEED_HOSTS}" | tr ',' ' '); do
    [ -n "${REQUESTER}" ] && REQUESTER="${REQUESTER},"
    REQUESTER="${REQUESTER}http://${_hp}"
done
POS_GOSSIP_PEERS="${SEED_HOSTS}"

# Gossip block mesh (host:gossipPort of every node's SERVER gossip listener).
# Defaults to the per-node port scheme (9095 + 2*index) matched to SEED_HOSTS
# order; override GOSSIP_SEEDS in common.env for custom port mappings.
if [ -z "${GOSSIP_SEEDS:-}" ]; then
    GOSSIP_SEEDS=""
    _gi=0
    for _hp in $(echo "${SEED_HOSTS}" | tr ',' ' '); do
        _h="${_hp%%:*}"
        [ -n "${GOSSIP_SEEDS}" ] && GOSSIP_SEEDS="${GOSSIP_SEEDS},"
        GOSSIP_SEEDS="${GOSSIP_SEEDS}${_h}:$((9095 + _gi * 2))"
        _gi=$((_gi + 1))
    done
fi

log()  { echo "[node-${NODE_INDEX}] $*"; }

http_post() { # $1=path  $2=json-body
    curl -sS -X POST "${API_BASE}${1}" -H 'Content-Type: application/json' -d "$2"
}

# ---- Database --------------------------------------------------------------
db_setup() {
    log "creating database ${DB_NAME} (if absent)"
    # Connect to the always-present 'postgres' DB to run CREATE DATABASE (psql
    # without -d would try the user-named DB, which does not exist on a fresh
    # postgres image).
    PGPASSWORD="${DB_PASSWORD}" psql -h "${DB_HOSTNAME}" -p "${DB_PORT}" -U "${DB_USERNAME}" -d postgres \
        -tc "SELECT 1 FROM pg_database WHERE datname='${DB_NAME}'" | grep -q 1 \
        || PGPASSWORD="${DB_PASSWORD}" psql -h "${DB_HOSTNAME}" -p "${DB_PORT}" -U "${DB_USERNAME}" -d postgres \
             -c "CREATE DATABASE ${DB_NAME};"
}

# ---- Processes -------------------------------------------------------------
docker_run() { # $1=container-name  $2=image  rest=java command args
    local name="$1" image="$2"; shift 2
    docker rm -f "${name}" >/dev/null 2>&1 || true
    log "starting container ${name} (${image})"
    docker run -d --name "${name}" \
        --network "${DOCKER_NETWORK}" \
        ${DOCKER_RUN_FLAGS:-} \
        --entrypoint java \
        "${image}" \
        "$@" >/dev/null
    nohup docker logs -f "${name}" > "${name}.log" 2>&1 &
    echo "${name}" > "${name}.cid"
}

start_server() {
    local createtable="${CREATETABLE:-true}"
    local genesis_csv_arg=()
    if [ -n "${GENESIS_CSV:-}" ]; then
        genesis_csv_arg=("-Dbigtangle.genesis.csv=${GENESIS_CSV}")
    fi
    log "starting layer0-server on :${SERVER_PORT} (db=${DB_NAME})"
    docker_run "node-${NODE_INDEX}-server" "${SERVER_IMAGE}:${IMAGE_TAG}" \
        ${JAVA_OPTS_SERVER} "${genesis_csv_arg[@]}" -jar /app/app.jar \
        --server.port="${SERVER_PORT}" --server.address="${NODE_HOST}" \
        --server.net="${SERVER_NET}" --server.chain="${SERVER_CHAIN}" \
        --store.domain="${STORE_DOMAIN}" \
        --db.hostname="${DB_HOSTNAME}" --db.port="${DB_PORT}" --db.dbName="${DB_NAME}" \
        --db.username="${DB_USERNAME}" --db.password="${DB_PASSWORD}" \
        --server.createtable="${createtable}" \
        --server.runKafkaStream=false \
        --server.fundEnabled="${FUND_ENABLED:-false}" \
        --server.requester="${REQUESTER}" \
        --service.schedule.chainlength=true --service.schedule.blockbatch=true \
        --service.schedule.microbatch=true --service.schedule.initsync=true \
        --peer.udpPort="${SERVER_PEER_UDP}" --peer.tcpPort="${SERVER_PEER_TCP}" --gossip.port="${SERVER_GOSSIP}" \
        --gossip.peers="${GOSSIP_SEEDS}" \
        --pos.validatorKey="${POS_VALIDATOR_KEY}" --pos.dutyEnabled=true \
        --pos.gossipPeers="${POS_GOSSIP_PEERS}"
}

# ---- API helpers -----------------------------------------------------------
wait_api() {
    log "waiting for API ${API_BASE}"
    for _ in $(seq 1 120); do
        if curl -sf "${API_BASE}/" >/dev/null 2>&1; then return 0; fi
        sleep 3
    done
    log "API did not come up"; return 1
}

balance_big() { # sums confirmed BIG balance for PUBKEY_HASH
    http_post "/getBalances" "[\"${PUBKEY_HASH}\"]" | python3 -c '
import sys, json, base64
try:
    d = json.load(sys.stdin)
except Exception:
    print(0); sys.exit(0)
# tokenid is the byte[] serialized as base64: BIGTANGLE_TOKENID = {0xbc} ->
# base64 "vA==". Match the decoded bytes (same source as the wallet/prodsim).
want = b"\xbc"
total = 0
for u in d.get("outputs") or []:
    v = (u or {}).get("value") or {}
    if isinstance(v, dict) and base64.b64decode(v.get("tokenid") or "") == want:
        total += int(v.get("value") or 0)
print(total)
'
}

wait_balance() { # $1 = min satoshis
    log "waiting for confirmed balance >= ${1}"
    for _ in $(seq 1 80); do
        b=$(balance_big)
        if [ "${b:-0}" -ge "$1" ]; then log "balance=${b}"; return 0; fi
        sleep 3
    done
    log "balance never reached ${1}"; return 1
}

# ---- Bootstrap -------------------------------------------------------------
fund_validator() {
    if [ "${FUND_MODE}" != "bootstrap" ]; then
        log "FUND_MODE=${FUND_MODE}; skipping fundAddresses"
        return 0
    fi
    log "funding validator via fundAddresses (${FUND_AMOUNT} satoshis)"
    # Unique per-node fund index so each validator's STAKE block spends a
    # DIFFERENT genesis outpoint. Without it every node's first fundAddresses
    # call mints at (genesis, 1e9) and a beacon referencing all three STAKE
    # blocks is rejected as conflicting (chain stalls at chainlength 0).
    local fund_index=$((1000000000 + NODE_INDEX))
    http_post "/fundAddresses" \
        "{\"addresses\":[{\"address\":\"validator\",\"value\":${FUND_AMOUNT},\"pubkey\":\"${VALIDATOR_PUBKEY}\",\"index\":${fund_index}}]}"
}

stake_validator() {
    log "staking ${STAKE_AMOUNT} satoshis for ${VALIDATOR_PUBKEY}"
    http_post "/stakeDeposit" "{\"pubkey\":\"${VALIDATOR_PUBKEY}\",\"amount\":\"${STAKE_AMOUNT}\"}"
}

activate_validator() {
    log "activating validator at epoch ${ACTIVATE_EPOCH:-0}"
    http_post "/activateValidator" "{\"pubkey\":\"${VALIDATOR_PUBKEY}\",\"epoch\":${ACTIVATE_EPOCH:-0}}"
}

# ---- Phased bootstrap (production ordering) --------------------------------
# Validator duties (beacon proposals) run ON the layer0-server itself in PoS
# mode, so beacon production ramps up as soon as the first validator enables
# duties. Operators run the phases in order across ALL nodes:
# server → stake → verify.
phase_server() {
    db_setup
    start_server
    wait_api
    log "server up"
}

phase_stake() {
    wait_api
    [ "${FUND_MODE}" = "bootstrap" ] && fund_validator
    wait_balance "${STAKE_AMOUNT}"
    stake_validator
    sleep 3
    activate_validator
    sleep 3
    log "staked + activated ${VALIDATOR_PUBKEY}"
}

# Cross-node acceptance: every node reports the full active set (nothing
# slashed/reverted), every node has confirmed a beacon, and the confirmed
# chainlengths agree within one epoch.
verify_network() {
    local expected="${EXPECTED_VALIDATORS:-$(echo "${SEED_HOSTS}" | tr ',' '\n' | wc -l | tr -d ' ')}"
    local epoch="${POS_SLOTS_PER_EPOCH:-32}"
    local maxcl=0 mincl=999999999
    log "verifying ${expected}-node network across: ${SEED_HOSTS}"
    for hostport in $(echo "${SEED_HOSTS}" | tr ',' ' '); do
        local base="http://${hostport}"
        local v cl
        v=$(curl -s -X POST "${base}/getValidators" -H 'Content-Type: application/json' -d '{}' \
            | python3 -c 'import sys,json; d=json.load(sys.stdin); v=d.get("text") or d.get("validators"); import json as j; v=j.loads(v) if isinstance(v,str) else v; v=(v or {}).get("validators") if isinstance(v,dict) else v; print(len(v) if v is not None else 0)' 2>/dev/null || echo 0)
        cl=$(curl -s -X POST "${base}/getChainNumber" -H 'Content-Type: application/json' -d '{}' \
            | python3 -c 'import sys,json; d=json.load(sys.stdin); r=d.get("txReward"); import json as j; r=j.loads(r) if isinstance(r,str) else r; print((r or {}).get("chainLength",0))' 2>/dev/null || echo 0)
        echo "  ${hostport}: validators=${v} chainlength=${cl}"
        [ "${v}" -lt "${expected}" ] && { echo "  FAIL: ${hostport} has ${v}/${expected} validators" >&2; return 1; }
        [ "${cl}" -gt "${maxcl}" ] && maxcl="${cl}"
        [ "${cl}" -lt "${mincl}" ] && mincl="${cl}"
    done
    [ "${maxcl}" -eq 0 ] && { echo "  FAIL: no node has confirmed a beacon" >&2; return 1; }
    [ $((maxcl - mincl)) -gt "${epoch}" ] && { echo "  FAIL: confirmed chainlength spread ${mincl}..${maxcl} > ${epoch}" >&2; return 1; }
    log "OK: ${expected} validators active on all nodes; confirmed chainlength ${mincl}..${maxcl}"
}

run_phase() { # $1 = server | stake | verify
    case "$1" in
        server) phase_server ;;
        stake)  phase_stake ;;
        verify) verify_network ;;
        *) echo "usage: setup.sh <server|stake|verify>" >&2; return 2 ;;
    esac
}
