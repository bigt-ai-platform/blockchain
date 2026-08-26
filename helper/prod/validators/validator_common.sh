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
# Exclude SELF from the requester list: a cold-starting node's own API
# answers 'service is not ready' until startInit completes, and startInit
# pulls through the requester endpoints — self-inclusion deadlocks boot.
# The grep must be || true-guarded inside its pipe stage: with NNODES=1 the
# only candidate IS self, so grep exits 'no match' (1) and set -o pipefail
# turned that into a silent sourcing failure of the whole file.
REQUESTER="$(echo "${REQUESTER}" | tr ',' '\n' | { grep -v "^http://${NODE_HOST}:${SERVER_PORT}$" || true; } | paste -sd, -)"
# Drop requesters that are unreachable right now: a hanging TCP connect
# stalls the initial chain scan; periodic sync re-tests them later.
_REQ_OUT=""
for _u in $(echo "${REQUESTER}" | tr ',' ' '); do
    _pair="${_u#http://}"
    if timeout 2 bash -c "</dev/tcp/${_pair%%:*}/${_pair##*:}" 2>/dev/null; then
        _REQ_OUT="${_REQ_OUT}${_REQ_OUT:+,}${_u}"
    else
        # log() is defined further down; this runs during sourcing.
        echo "[node-${NODE_INDEX}] requester ${_u} unreachable at boot; skipping"
    fi
done
# if-form (not [ ] && ) — an empty result must not trip set -e and abort the
# sourced script (observed: NNODES=1 has zero peers, so the self-exclusion
# above leaves _REQ_OUT empty and a bare && chain killed startup silently).
if [ -n "$_REQ_OUT" ]; then REQUESTER="$_REQ_OUT"; fi
unset _REQ_OUT _u _pair
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
PG_CONTAINER="${PG_CONTAINER:-test-bigtangle-postgres}"
_pg() { # psql wrapper: local client when present, else exec into the DB container
    if command -v psql >/dev/null 2>&1; then
        PGPASSWORD="${DB_PASSWORD}" psql -h "${DB_HOSTNAME}" -p "${DB_PORT}" -U "${DB_USERNAME}" "$@"
    elif docker ps --format '{{.Names}}' 2>/dev/null | grep -qx "${PG_CONTAINER}"; then
        # The container's own listen port is DB_PORT in this deployment.
        docker exec "${PG_CONTAINER}" psql -h localhost -p "${DB_PORT}" -U "${DB_USERNAME}" "$@"
    else
        echo "no psql client and no container ${PG_CONTAINER}" >&2; return 1
    fi
}
db_setup() {
    log "creating database ${DB_NAME} (if absent)"
    # Connect to the always-present 'postgres' DB to run CREATE DATABASE (psql
    # without -d would try the user-named DB, which does not exist on a fresh
    # postgres image).
    _pg -d postgres \
        -tc "SELECT 1 FROM pg_database WHERE datname='${DB_NAME}'" | grep -q 1 \
        || _pg -d postgres -c "CREATE DATABASE ${DB_NAME};"
}

# ---- Processes -------------------------------------------------------------
docker_run() { # $1=container-name  $2=image  rest=java command args
    local name="${CONTAINER_PREFIX:-}${1}" image="$2"; shift 2
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
    # Kafka stream processing off by default; nodes with KAFKA_BOOTSTRAP set
    # (e.g. s2001:9092, written by addnode.sh) consume the block stream.
    local kafka_args=("--server.runKafkaStream=false")
    if [ -n "${KAFKA_BOOTSTRAP:-}" ]; then
        kafka_args=("--server.runKafkaStream=true" "--kafka.bootstrapServers=${KAFKA_BOOTSTRAP}")
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
        "${kafka_args[@]}" \
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

# Cross-node acceptance. Safety = all nodes that have finalized agree on the
# SAME finalized checkpoint (different finalized roots = incompatible chains;
# PoS never reorgs finality — that is a hard FAIL). A trailing confirmed tip
# is mere throughput lag, not wrongness: it is reported and only WARNs past
# one epoch. Also fails on missing validators / zero beacons anywhere.
verify_network() {
    local expected="${EXPECTED_VALIDATORS:-$(echo "${SEED_HOSTS}" | tr ',' '\n' | wc -l | tr -d ' ')}"
    local epoch="${POS_SLOTS_PER_EPOCH:-32}"
    local maxcl=0 mincl=999999999
    local finlist=""
    local -A FIN_RAW=() FIN_GRP=()
    log "verifying ${expected}-node network across: ${SEED_HOSTS}"
    for hostport in $(echo "${SEED_HOSTS}" | tr ',' ' '); do
        local base="http://${hostport}"
        local v cl fin flen
        v=$(curl -s -m 5 -X POST "${base}/getValidators" -H 'Content-Type: application/json' -d '{}' \
            | python3 -c 'import sys,json; d=json.load(sys.stdin); v=d.get("text") or d.get("validators"); import json as j; v=j.loads(v) if isinstance(v,str) else v; v=(v or {}).get("validators") if isinstance(v,dict) else v; print(len(v) if v is not None else 0)' 2>/dev/null || echo 0)
        read -r cl fin flen finraw <<<"$(curl -s -m 5 -X POST "${base}/getChainNumber" -H 'Content-Type: application/json' -d '{}' \
            | python3 -c '
import sys, json, hashlib
try:
    d = json.load(sys.stdin)
except Exception:
    print("0 - - -"); sys.exit(0)
r = d.get("txReward")
r = json.loads(r) if isinstance(r, str) else (r or {})
f = d.get("finalizedBlockHash") or ""
raw = (f["bytes"] if isinstance(f, dict) else str(f)).strip()
fin = hashlib.sha256(raw.encode()).hexdigest()[:16] if raw else "-"
flen = d.get("finalizedChainLength")
print(r.get("chainLength", 0), fin, flen if flen is not None else "-", raw)' 2>/dev/null || echo "0 - - -")"
        echo "  ${hostport}: validators=${v} chainlength=${cl:-?} finalized=${fin}#${flen:-?}"
        [ "${v}" -lt "${expected}" ] && { echo "  FAIL: ${hostport} has ${v}/${expected} validators" >&2; return 1; }
        [ "${cl:-0}" -gt "${maxcl}" ] && maxcl="${cl}"
        [ "${cl:-0}" -lt "${mincl}" ] && mincl="${cl}"
        if [ -n "$fin" ] && [ "$fin" != "-" ]; then
            finlist="${finlist}${fin}\n"
            FIN_RAW[${hostport}]="${finraw:-}"
            FIN_GRP[${hostport}]="$fin"
        fi
    done
    [ "${maxcl}" -eq 0 ] && { echo "  FAIL: no node has confirmed a beacon" >&2; return 1; }
    local distinct
    distinct=$(printf '%b' "$finlist" | sort -u | grep -c . || true)
    if [ "${distinct}" -gt 1 ]; then
        # Differing finalized roots are only fatal if INCOMPATIBLE: a slow node
        # legitimately trails on an OLDER checkpoint of the same chain. Probe
        # each minority checkpoint's block against the peers — present on a
        # peer = ancestral/same-chain (WARN); absent everywhere = true split.
        local split=0 hp2 hex probe
        local nseeds
        nseeds=$(echo "${SEED_HOSTS}" | tr ',' ' ' | wc -w | tr -d ' ')
        local thresh=$(( nseeds / 2 + 1 ))
        for hp2 in $(echo "${SEED_HOSTS}" | tr ',' ' '); do
            [ -z "${FIN_GRP[${hp2}]:-}" ] && continue
            # skip hosts belonging to the majority group
            local cnt=0
            for g in $(printf '%b' "$finlist"); do [ "$g" = "${FIN_GRP[${hp2}]}" ] && cnt=$((cnt+1)); done
            [ "${cnt}" -ge "${thresh}" ] && continue
            hex="${FIN_RAW[${hp2}]}"   # finalizedBlockHash arrives as plain hex
            [ -z "$hex" ] && continue
            probe=""
            for peer in $(echo "${SEED_HOSTS}" | tr ',' ' '); do
                [ "$peer" = "$hp2" ] && continue
                body=$(curl -s -m 5 -X POST "http://${peer}/getBlockByHash" \
                    -H 'Content-Type: application/json' -d "{\"hashHex\":\"${hex}\",\"text\":\"true\"}" 2>/dev/null || echo "")
                # miss is signaled in the body (errorcode 404), HTTP stays 200.
                # 'hash:' appears in every block toString, even INITIAL/genesis
                # ones which omit the blocktype line.
                if echo "$body" | grep -q 'hash:'; then
                    probe="found-on:${peer}"
                    break
                fi
            done
            if [ -z "$probe" ]; then
                echo "  FAIL: ${hp2} finalized ${FIN_GRP[${hp2}]} exists on NO peer — chain split" >&2
                split=1
            else
                echo "  WARN: ${hp2} trails on older finalized checkpoint (${FIN_GRP[${hp2}]}, ${probe})"
            fi
        done
        [ "${split}" = "1" ] && return 1
    fi
    if [ $((maxcl - mincl)) -gt "${epoch}" ]; then
        echo "  WARN: confirmed-tip spread ${mincl}..${maxcl} > ${epoch} — lagging node(s), same finalized root" >&2
    fi
    log "OK: ${expected} validators active on all nodes; confirmed tips ${mincl}..${maxcl}; finalized $(printf '%b' "$finlist" | head -1)"
}

run_phase() { # $1 = server | stake | verify
    case "$1" in
        server) phase_server ;;
        stake)  phase_stake ;;
        verify) verify_network ;;
        *) echo "usage: setup.sh <server|stake|verify>" >&2; return 2 ;;
    esac
}
