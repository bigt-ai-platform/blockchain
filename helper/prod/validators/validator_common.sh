#!/usr/bin/env bash
# Shared functions for per-validator setup.
# Sourced after common.env and node-<i>/validator.env, which must define:
#   NODE_INDEX, NODE_HOST, POS_VALIDATOR_KEY, VALIDATOR_PUBKEY, PUBKEY_HASH
set -euo pipefail

# ---- Derived per-node values ----------------------------------------------
SERVER_PORT=$((8081 + NODE_INDEX))
SERVER_PEER_UDP=$((30307 + NODE_INDEX * 2))
SERVER_PEER_TCP=$((30308 + NODE_INDEX * 2))
SERVER_GOSSIP=$((9095 + NODE_INDEX * 2))

MCMC_PORT=$((8091 + NODE_INDEX))
MCMC_PEER_UDP=$((30309 + NODE_INDEX * 2))
MCMC_PEER_TCP=$((30310 + NODE_INDEX * 2))
MCMC_GOSSIP=$((9097 + NODE_INDEX * 2))

if [ "$NODE_INDEX" = "0" ]; then DB_NAME=layer0; else DB_NAME="layer0_${NODE_INDEX}"; fi
API_BASE="http://${NODE_HOST}:${SERVER_PORT}"

# Seed/peer derivation: REQUESTER points at the first seed's API server;
# POS_GOSSIP_PEERS is the full attestation mesh (every validator's server).
FIRST_SEED_HOST="$(echo "${SEED_HOSTS}" | cut -d, -f1)"
REQUESTER="http://${FIRST_SEED_HOST}"
POS_GOSSIP_PEERS="${SEED_HOSTS}"
CREATETABLE="true"

log()  { echo "[node-${NODE_INDEX}] $*"; }

http_post() { # $1=path  $2=json-body
    curl -sS -X POST "${API_BASE}${1}" -H 'Content-Type: application/json' -d "$2"
}

# ---- Database --------------------------------------------------------------
db_setup() {
    log "creating database ${DB_NAME} (if absent)"
    PGPASSWORD="${DB_PASSWORD}" psql -h "${DB_HOSTNAME}" -p "${DB_PORT}" -U "${DB_USERNAME}" \
        -tc "SELECT 1 FROM pg_database WHERE datname='${DB_NAME}'" | grep -q 1 \
        || PGPASSWORD="${DB_PASSWORD}" psql -h "${DB_HOSTNAME}" -p "${DB_PORT}" -U "${DB_USERNAME}" \
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
        --server.port="${SERVER_PORT}" --server.net="${SERVER_NET}" --server.chain="${SERVER_CHAIN}" \
        --db.hostname="${DB_HOSTNAME}" --db.port="${DB_PORT}" --db.dbName="${DB_NAME}" \
        --db.username="${DB_USERNAME}" --db.password="${DB_PASSWORD}" --db.dbtype="${DBTYPE}" \
        --server.createtable="${createtable}" \
        --server.runKafkaStream=false \
        --server.fundEnabled="${FUND_ENABLED:-false}" \
        --service.schedule.mcmc=true --service.schedule.blockbatch=true \
        --service.schedule.microbatch=true --service.schedule.initsync=true \
        --peer.udpPort="${SERVER_PEER_UDP}" --peer.tcpPort="${SERVER_PEER_TCP}" --gossip.port="${SERVER_GOSSIP}" \
        --pos.validatorKey="${POS_VALIDATOR_KEY}" --pos.dutyEnabled=false \
        --pos.gossipPeers="${POS_GOSSIP_PEERS}"
}

start_mcmc() {
    log "starting layer0-mcmc on :${MCMC_PORT} (db=${DB_NAME})"
    docker_run "node-${NODE_INDEX}-mcmc" "${MCMC_IMAGE}:${IMAGE_TAG}" \
        ${JAVA_OPTS_MCMC} -jar /app/app.jar \
        --server.port="${MCMC_PORT}" --server.net="${SERVER_NET}" \
        --db.hostname="${DB_HOSTNAME}" --db.port="${DB_PORT}" --db.dbName="${DB_NAME}" \
        --db.username="${DB_USERNAME}" --db.password="${DB_PASSWORD}" --db.dbtype="${DBTYPE}" \
        --server.requester="${REQUESTER}" \
        --server.createtable=false \
        --server.runKafkaStream=false \
        --service.schedule.mcmc=true --service.schedule.blockbatch=true \
        --service.schedule.microbatch=true \
        --pos.validatorKey="${POS_VALIDATOR_KEY}" --pos.dutyEnabled=true \
        --pos.gossipPeers="${POS_GOSSIP_PEERS}" \
        --peer.udpPort="${MCMC_PEER_UDP}" --peer.tcpPort="${MCMC_PEER_TCP}" --gossip.port="${MCMC_GOSSIP}"
}

# ---- API helpers -----------------------------------------------------------
wait_api() {
    log "waiting for API ${API_BASE}"
    for _ in $(seq 1 120); do
        if curl -sf "${API_BASE}/getChainHeight" >/dev/null 2>&1; then return 0; fi
        sleep 3
    done
    log "API did not come up"; return 1
}

balance_big() { # sums confirmed BIG balance for PUBKEY_HASH
    http_post "/getBalances" "[\"${PUBKEY_HASH}\"]" | python3 -c '
import sys, json
try:
    d = json.load(sys.stdin)
except Exception:
    print(0); sys.exit(0)
bal = d.get("balance", [])
total = 0
for c in bal:
    if isinstance(c, dict) and c.get("tokenid") == "bc":
        total += int(c.get("value", 0))
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
    http_post "/fundAddresses" \
        "{\"addresses\":[{\"address\":\"validator\",\"value\":${FUND_AMOUNT},\"pubkey\":\"${VALIDATOR_PUBKEY}\"}]}"
}

stake_validator() {
    log "staking ${STAKE_AMOUNT} satoshis for ${VALIDATOR_PUBKEY}"
    http_post "/stakeDeposit" "{\"pubkey\":\"${VALIDATOR_PUBKEY}\",\"amount\":\"${STAKE_AMOUNT}\"}"
}

activate_validator() {
    log "activating validator at epoch ${ACTIVATE_EPOCH:-0}"
    http_post "/activateValidator" "{\"pubkey\":\"${VALIDATOR_PUBKEY}\",\"epoch\":${ACTIVATE_EPOCH:-0}}"
}

verify_validators() {
    log "active validator set:"
    http_post "/getValidators" "{}"
    echo
}

# ---- Full per-node flow ----------------------------------------------------
run_all() {
    db_setup
    start_server
    sleep 5
    start_mcmc
    wait_api
    fund_validator
    wait_balance "${STAKE_AMOUNT}"
    stake_validator
    sleep 3
    activate_validator
    sleep 3
    verify_validators
    log "done"
}
