#!/usr/bin/env bash
# testnodes.sh — hermetic local testnet (default 3 nodes, NNODES env to vary)
# for fast sync/kafka/finality/leave/join
# iteration. Runs everything on ONE host against the real validator_common.sh,
# on dedicated ports (82xx/93xx/94xx/304xx) so it never touches a live mesh.
#
# Usage:
#   testnodes.sh up        start pg+kafka (if needed) + 3 fresh nodes (wipe state)
#   testnodes.sh stake     fund + stakeDeposit + activateValidator on all nodes
#   testnodes.sh status    tips + finalized length per node (+ container states)
#   testnodes.sh verify    cross-node acceptance (validators, finalized root)
#   testnodes.sh finality [MIN]   wait until finalizedChainLength >= MIN (default 33)
#   testnodes.sh leave N   signed BLOCKTYPE_EXIT for node N, stop it, drop from seeds
#   testnodes.sh join N    (re-)create node N with FRESH keys, seed it, stake it
#   testnodes.sh transfer  submit one transfer via node-0, require visibility on all
#   testnodes.sh down      stop everything, drop test databases
#   testnodes.sh all       up → stake → finality → transfer → leave 2 → verify →
#                          join 2 → stake 2 → finality → verify  (full regression)
#
# Env overrides: WORKDIR (default /tmp/bt4test), IMAGE[:TAG] (server image),
# PGPORT (local postgres port, default 21532),
# PGDATA_ROOT (host path backing the postgres datadir,
#   default /data/vm/test-bigtangle-postgres/var/lib/postgresql/data — survives
#   container recreation and lives on the big disk, not the root fs),
# KAFKA (default localhost:9092),
# SLOT_MS (pos.slotIntervalMs, default 12000 — prod value. With the 8-slot
# epochs of MainNetParams finality ≈ 2 epochs ≈ 3.2 min. 6 s slots were
# tried for speed but split the mesh three times (nodes freeze on short
# forks at 3-5 nodes; 2 s forks even 3 nodes) — the receive→connect pipeline
# needs the full 12 s to adopt each proposer block reliably),
# READINESS_MIN (bigtangle.readinessTimeoutMinutes, default 10),
# XMX (per-node JVM heap, default 3g — 3 nodes ≈ 11G RSS, sized for a 16G host;
#   lower it on smaller machines, e.g. XMX=1200m testnodes.sh up).
set -euo pipefail

# Use Java 25 if available (classes are compiled for class file version 69)
if [ -x /tmp/opencode/jdk25/bin/java ]; then
    export JAVA_HOME=/tmp/opencode/jdk25
    export PATH=$JAVA_HOME/bin:$PATH
elif [ -x /home/jcui/.local/java-25/bin/java ]; then
    export JAVA_HOME=/home/jcui/.local/java-25
    export PATH=$JAVA_HOME/bin:$PATH
fi

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
VALSRC="${ROOT}/helper/prod/validators"
WORKDIR="${WORKDIR:-/tmp/bt4test}"
IMAGE="${IMAGE:-ghcr.io/bigt-ai-platform/layer0-server:latest}"
PGPORT="${PGPORT:-21532}"
PGDATA_ROOT="${PGDATA_ROOT:-/data/vm/test-bigtangle-postgres/var/lib/postgresql/data}"
KAFKA="${KAFKA:-localhost:9092}"
SLOT_MS="${SLOT_MS:-12000}"
READINESS_MIN="${READINESS_MIN:-10}"
XMX="${XMX:-3g}"
NNODES="${NNODES:-3}"
PGCONT=test-bigtangle-postgres
PGINNER=""

log()  { echo "[nodes] $*"; }
die()  { echo "[nodes] FAIL: $*" >&2; exit 1; }

api() { # $1=node-index $2=path $3=json-body → body or empty
    curl -s -m 5 -X POST "http://127.0.0.1:$((8281 + $1))${2}" \
        -H 'Content-Type: application/json' -d "${3}" 2>/dev/null || true
}
jget() { python3 -c "
import sys, json
try:
    d = json.load(sys.stdin)
except Exception:
    print('-'); sys.exit(0)
r = d.get('txReward'); r = json.loads(r) if isinstance(r, str) else (r or {})
key = '$1'
if key == 'chainLength': print(r.get('chainLength', 0))
elif key == 'finalized': print(d.get('finalizedChainLength') if d.get('finalizedChainLength') is not None else '-')
elif key == 'validators':
    v = d.get('text') or d.get('validators')
    v = json.loads(v) if isinstance(v, str) else v
    v = (v or {}).get('validators') if isinstance(v, dict) else v
    print(len(v or []))
else: print('-')" 2>/dev/null || echo -; }

cl_of()       { api "$1" /getChainNumber '{}' | jget chainLength; }
fin_of()      { api "$1" /getChainNumber '{}' | jget finalized; }
valcount_of() { api "$1" /getValidators '{}' | jget validators; }
head_of() { # confirmed head hash (short hex) of node $1
    api "$1" /getChainNumber '{}' | python3 -c "
import sys, json, base64
try:
    d = json.load(sys.stdin); r = d.get('txReward') or {}
    b = (r.get('blockHash') or {}).get('bytes')
    print(base64.b64decode(b).hex()[:16] if b else '-')
except Exception:
    print('-')" 2>/dev/null || echo -
}

pg_mount_ok() { # true when the running container already binds PGDATA_ROOT
    docker inspect "${PGCONT}" --format '{{range .Mounts}}{{.Source}}
{{end}}' 2>/dev/null | grep -Fxq "${PGDATA_ROOT}"
}

ensure_pg() {
    # Recreate unless the container is up AND persists its datadir on
    # PGDATA_ROOT: an ephemeral postgres silently loses all chain state on
    # restart, which turns every later run into a confusing full resync.
    if ! pg_mount_ok || ! (echo > /dev/tcp/127.0.0.1/"${PGPORT}") 2>/dev/null; then
        log "starting local postgres (${PGCONT} on :${PGPORT}, datadir ${PGDATA_ROOT})"
        docker rm -f ${PGCONT} >/dev/null 2>&1 || true
        mkdir -p "${PGDATA_ROOT}"
        docker run -d --name ${PGCONT} \
            -v "${PGDATA_ROOT}:/var/lib/postgresql/data" \
            -p "${PGPORT}:5432" -e POSTGRES_USER=root \
            -e POSTGRES_PASSWORD=test1234 -e POSTGRES_DB=info \
            postgres:16 -c max_connections=500 >/dev/null
        sleep 6
    fi
    # existing containers may listen on a non-5432 internal port (e.g. 21532)
    if docker exec ${PGCONT} psql -p "${PGPORT}" -U root -d postgres -tc 'SELECT 1' >/dev/null 2>&1; then
        PGINNER="${PGPORT}"
    elif docker exec ${PGCONT} psql -p ${PGINNER:-5432} -U root -d postgres -tc 'SELECT 1' >/dev/null 2>&1; then
        PGINNER=5432
    else
        die "postgres on :${PGPORT} unreachable"
    fi
}

ensure_kafka() {
    if ! (echo > /dev/tcp/127.0.0.1/9092) 2>/dev/null; then
        log "starting local kafka (bt4-kafka on :9092)"
        docker rm -f bt4-kafka >/dev/null 2>&1 || true
        docker run -d --name bt4-kafka -p 9092:9092 apache/kafka:3.9.0 >/dev/null
        sleep 8
    fi
    # kafka-topics is NOT on PATH in apache/kafka images — full path required
    local kt=(docker exec bt4-kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092)
    for _ in $(seq 1 30); do
        "${kt[@]}" --list >/dev/null 2>&1 && break
        sleep 1
    done
    # fresh topics every 'up': delete, wait until gone, recreate EMPTY.
    # Recreate is mandatory — Kafka Streams aborts with
    # MissingSourceTopicException when a source topic is absent during
    # assignment (broker auto-create only fires on produce, which races
    # stream startup and loses).
    local t
    for t in bigtangle-blocks bigtangle-transactions bigtangle-attestations; do
        "${kt[@]}" --delete --topic "$t" >/dev/null 2>&1 || true
        for _ in $(seq 1 30); do
            "${kt[@]}" --list 2>/dev/null | grep -qx "$t" || break
            sleep 1
        done
        # create races the async delete (TopicExistsException while the
        # topic is still 'deleting' broker-side) — retry until it sticks.
        local created=0 a
        for a in 1 2 3 4 5 6 7 8; do
            "${kt[@]}" --create --topic "$t" --partitions 1 --replication-factor 1 >/dev/null 2>&1 && { created=1; break; }
            sleep 3
        done
        [ "$created" = 1 ] || die "could not recreate kafka topic $t"
    done
}

gen_keys() { # → ValidatorKeyTool output lines (uses exec jar, falls back to image)
    local jar
    jar="$(ls -t "${ROOT}"/layer0-server/target/layer0-server-*-exec.jar 2>/dev/null | head -1 || true)"
    if [ -n "$jar" ]; then
        local dir="${WORKDIR}/cp"
        mkdir -p "$dir"
        if [ ! -f "${dir}/.stamp" ] || [ "$jar" -nt "${dir}/.stamp" ]; then
            rm -rf "${dir}/BOOT-INF" "${dir}/.stamp"
            unzip -oq "$jar" 'BOOT-INF/*' -d "$dir" && touch "${dir}/.stamp"
        fi
        java -cp "${dir}/BOOT-INF/classes:${dir}/BOOT-INF/lib/*" net.bigtangle.tools.ValidatorKeyTool generate
    else
        docker run --rm --network none --entrypoint java "$IMAGE" \
            -cp /app/app.jar net.bigtangle.tools.ValidatorKeyTool generate
    fi
}

gen_keys_for_seed() { # $1=seedHex → ValidatorKeyTool pubkey output lines (fixed identity)
    local jar
    jar="$(ls -t "${ROOT}"/layer0-server/target/layer0-server-*-exec.jar 2>/dev/null | head -1 || true)"
    if [ -n "$jar" ]; then
        local dir="${WORKDIR}/cp"
        mkdir -p "$dir"
        if [ ! -f "${dir}/.stamp" ] || [ "$jar" -nt "${dir}/.stamp" ]; then
            rm -rf "${dir}/BOOT-INF" "${dir}/.stamp"
            unzip -oq "$jar" 'BOOT-INF/*' -d "$dir" && touch "${dir}/.stamp"
        fi
        java -cp "${dir}/BOOT-INF/classes:${dir}/BOOT-INF/lib/*" net.bigtangle.tools.ValidatorKeyTool pubkey "$1"
    else
        docker run --rm --network none --entrypoint java "$IMAGE" \
            -cp /app/app.jar net.bigtangle.tools.ValidatorKeyTool pubkey "$1"
    fi
}

make_node_env() { # $1=index
    local i="$1" out key pub hash addr
    out="$(gen_keys)"
    key="$(echo "$out" | grep '^POS_VALIDATOR_KEY=' | cut -d= -f2-)"
    pub="$(echo "$out" | grep '^VALIDATOR_PUBKEY=' | cut -d= -f2-)"
    hash="$(echo "$out" | grep '^PUBKEY_HASH=' | cut -d= -f2-)"
    addr="$(echo "$out" | grep '^ADDRESS=' | cut -d= -f2-)"
    [ -n "$key" ] || die "key generation failed"
    cat > "${WORKDIR}/node-${i}/validator.env" <<EOF
NODE_INDEX=${i}
NODE_HOST=127.0.0.1
CONTAINER_PREFIX=bt4-node-
POS_VALIDATOR_KEY=${key}
VALIDATOR_PUBKEY=${pub}
PUBKEY_HASH=${hash}
ADDRESS=${addr}
SERVER_PORT=$((8281 + i))
MCMC_PORT=$((8381 + i))
SERVER_PEER_UDP=$((30407 + i * 2))
SERVER_PEER_TCP=$((30408 + i * 2))
SERVER_GOSSIP=$((9421 + i))
DB_NAME=bt4_${i}
DB_PORT=${PGPORT}
KAFKA_BOOTSTRAP=${KAFKA}
JAVA_OPTS_SERVER="-Xmx${XMX} -Dbigtangle.readinessTimeoutMinutes=${READINESS_MIN} -Dpos.slotIntervalMs=${SLOT_MS} -Dnet.bigtangle.pos.attestationActivation=1 -Dpos.warmupSlots=${WARMUP_SLOTS:-0} -Ddb.pool.mainMaxSize=${DBPOOL_MAIN:-48} -Ddb.pool.posMaxSize=${DBPOOL_POS:-24}${MEMPOOL_MAX_TX:+ -Dserver.mempoolMaxTx=${MEMPOOL_MAX_TX}}${BATCH_TX_PER_BLOCK:+ -Dbatch.txPerBlock=${BATCH_TX_PER_BLOCK}}"
EOF
    chmod 600 "${WORKDIR}/node-${i}/validator.env"
}

cmd_up() {
    mkdir -p "$WORKDIR"
    # stop leftover node containers FIRST: they keep producing to the kafka
    # topics (resurrecting them mid delete/create) and hold the old DBs.
    for i in $(seq 0 $((NNODES - 1))); do
        docker rm -f "bt4-node-node-${i}-server" >/dev/null 2>&1 || true
    done
    ensure_pg; ensure_kafka
    # hermetic common.env — same shape as prod, localhost-only
    seeds=""; gossip=""
    for i in $(seq 0 $((NNODES - 1))); do
        seeds="${seeds}${seeds:+,}127.0.0.1:$((8281 + i))"
        gossip="${gossip}${gossip:+,}127.0.0.1:$((9421 + i))"
    done
    cat > "${WORKDIR}/common.env" <<EOF
SERVER_NET=Mainnet
SERVER_CHAIN=L0
STORE_DOMAIN=core
SERVER_IMAGE=${IMAGE%%:*}
IMAGE_TAG=${IMAGE##*:}
DB_HOSTNAME=localhost
DB_USERNAME=root
DB_PASSWORD=test1234
DB_PORT=${PGPORT}
PG_CONTAINER=${PGCONT}
DOCKER_NETWORK=host
SEED_HOSTS="${seeds}"
GOSSIP_SEEDS="${gossip}"
STAKE_AMOUNT=32000000
FUND_AMOUNT=160000000
GENESIS_CSV=
# MUST match the nodes' params: SERVER_NET=Mainnet -> MainNetParams has
# slotsPerEpoch=8 (TestParams would be 32). Drives the leave poll timeout,
# the join stamp gate and verify_network's epoch math.
POS_SLOTS_PER_EPOCH=8
JAVA_OPTS_SERVER="-Xmx${XMX} -Dnet.bigtangle.pos.attestationActivation=1 -Dpos.warmupSlots=${WARMUP_SLOTS:-0} -Ddb.pool.mainMaxSize=${DBPOOL_MAIN:-48} -Ddb.pool.posMaxSize=${DBPOOL_POS:-24}"
EOF
    # the test drives the REAL shared machinery
    cp "${VALSRC}/validator_common.sh" "${WORKDIR}/validator_common.sh"
    for i in $(seq 0 $((NNODES - 1))); do
        rm -rf "${WORKDIR}/node-${i}"; mkdir -p "${WORKDIR}/node-${i}"
        make_node_env "$i"
    done
    # The /fundAddresses faucet has been removed — wallets must be funded at
    # GENESIS via a genesis distribution CSV (see UtilGeneseBlock). Every node
    # boots the SAME shared CSV so the genesis block is identical chain-wide.
    GENESIS_FUND="${GENESIS_FUND:-1000000000000}"
    # A staked validator's funds are BONDED (locked) until exit, so transfers /
    # join-funding must come from a dedicated non-staked wallet. Fund a fixed
    # test wallet (seed below) at genesis; testnodes holds its seed to sign.
    TESTWALLET_SEED="${TESTWALLET_SEED:-0707070707070707070707070707070707070707070707070707070707070707}"
    TESTWALLET_FUND="${TESTWALLET_FUND:-1000000000000000}"
    {
        echo "POS_VALIDATOR_KEY=${TESTWALLET_SEED}"
        gen_keys_for_seed "$TESTWALLET_SEED" | grep -E '^(VALIDATOR_PUBKEY|PUBKEY_HASH|ADDRESS)='
    } > "${WORKDIR}/testwallet.env"
    tw_pub="$(grep '^VALIDATOR_PUBKEY=' "${WORKDIR}/testwallet.env" | cut -d= -f2-)"
    [ -n "$tw_pub" ] || die "test wallet key derivation failed"
    {
        echo "address,pubkey,value"
        for i in $(seq 0 $((NNODES - 1))); do
            pub="$(grep '^VALIDATOR_PUBKEY=' "${WORKDIR}/node-${i}/validator.env" | cut -d= -f2-)"
            echo ",${pub},${GENESIS_FUND}"
        done
        echo ",${tw_pub},${TESTWALLET_FUND}"
    } > "${WORKDIR}/genesis.csv"
    sed -i "s#^GENESIS_CSV=.*#GENESIS_CSV=${WORKDIR}/genesis.csv#" "${WORKDIR}/common.env"
    # stop leftover node containers FIRST: DROP DATABASE silently fails
    # while a previous run's servers still hold connections, resurrecting
    # stale chain state on the "fresh" databases.
    for i in $(seq 0 $((NNODES - 1))); do
        docker rm -f "bt4-node-node-${i}-server" >/dev/null 2>&1 || true
    done
    for i in $(seq 0 $((NNODES - 1))); do
        docker exec ${PGCONT} psql -p ${PGINNER:-5432} -U root -d postgres -c "DROP DATABASE IF EXISTS bt4_${i};" >/dev/null 2>&1 || true
        docker exec ${PGCONT} psql -p ${PGINNER:-5432} -U root -d postgres -c "CREATE DATABASE bt4_${i};" >/dev/null 2>&1 || true
    done
    log "starting ${NNODES} nodes (image ${IMAGE}, slots ${SLOT_MS}ms)"
    for i in $(seq 0 $((NNODES - 1))); do
        ( cd "${WORKDIR}/node-${i}" && bash -c "
            set -euo pipefail
            source ../common.env; source ./validator.env
            source ../validator_common.sh
            start_server" ) > "${WORKDIR}/node-${i}/start.log" 2>&1 &
    done
    wait
    # Nodes boot in parallel — poll all pending APIs each round instead of
    # waiting out node-0's full window before even looking at node-1.
    local ok=0 pending="" i
    for i in $(seq 0 $((NNODES - 1))); do pending="${pending} $i"; done
    for _ in $(seq 1 100); do
        local still=""
        for i in $pending; do
            if [ "$(curl -s -m 2 -o /dev/null -w '%{http_code}' "http://127.0.0.1:$((8281 + i))/" 2>/dev/null)" = "200" ]; then
                ok=$((ok+1))
            else
                still="${still} $i"
            fi
        done
        pending="$still"
        [ -z "$pending" ] && break
        sleep 3
    done
    [ "$ok" = "$NNODES" ] || die "only ${ok}/${NNODES} APIs up — see ${WORKDIR}/node-*/start.log"
    log "up: ${NNODES} nodes serving"
}

wait_synced() { # $1=index — block until node $1's confirmed tip reaches the
    # best tip of the other running nodes. Staking/activating an UNSYNCED
    # node makes it propose beacons on its own short fork (observed: a
    # rejoined node at cl 2-3 proposing into a cl-500 mesh), and its
    # minted balance only becomes visible once sync reconnects the caches.
    local i="$1" target=0 j cl waited=0
    for j in $(seq 0 $((NNODES - 1))); do
        [ "$j" = "$i" ] && continue
        docker inspect "bt4-node-node-${j}-server" >/dev/null 2>&1 || continue
        cl="$(cl_of "$j")"
        [ -n "$cl" ] && [ "$cl" != "-" ] && [ "$cl" -gt "$target" ] 2>/dev/null && target="$cl"
    done
    [ "$target" -le 0 ] && return 0
    log "node-${i}: waiting for chain sync to peer tip ${target}"
    cl=0
    while [ "$waited" -lt 600 ]; do
        cl="$(cl_of "$i")"
        [ -n "$cl" ] && [ "$cl" != "-" ] && [ "$cl" -ge "$target" ] 2>/dev/null && \
            { log "node-${i} synced (cl=${cl}) after ${waited}s"; return 0; }
        sleep 10; waited=$((waited + 10))
    done
    log "node-${i} did not reach peer tip ${target} in 600s (cl=${cl})"
    return 1
}

phase_stake_one() { # $1=index
    wait_synced "$1"
    ( cd "${WORKDIR}/node-$1" && bash -c "
        set -euo pipefail
        source ../common.env; source ./validator.env
        source ../validator_common.sh
        wait_api
        wait_balance \${STAKE_AMOUNT}
        stake_validator >/dev/null; sleep 2; activate_validator >/dev/null" )
}

cmd_stake() {
    # Nodes stake independently (own key, own fund index) — fund+stake them
    # concurrently instead of serializing three ~1 min bootstrap cycles.
    local i pids=""
    for i in $(seq 0 $((NNODES - 1))); do
        log "staking node-${i}"
        phase_stake_one "$i" &
        pids="${pids} $!"
    done
    local rc=0 p
    for p in $pids; do wait "$p" || rc=1; done
    [ "$rc" = 0 ] || die "one or more stake phases failed"
    sleep 5
    log "staked: $(for i in $(seq 0 $((NNODES - 1))); do printf '%s ' "$(valcount_of "$i")"; done)validators seen"
}

cmd_status() {
    local heads="" i h
    for i in $(seq 0 $((NNODES - 1))); do
        h="$(head_of "$i")"
        printf '  node-%d  cl=%-6s finalized=%-6s validators=%s head=%s\n' "$i" "$(cl_of "$i")" "$(fin_of "$i")" "$(valcount_of "$i")" "$h"
        [ -n "$h" ] && [ "$h" != "-" ] && heads="${heads}${h}
"
    done
    # DIVERGENCE BANNER: distinct confirmed heads at this moment = the mesh is
    # split; every distinct head is one conflicting version of the chain.
    local distinct
    distinct=$(printf '%s' "$heads" | sort -u | grep -c . || true)
    if [ "${distinct:-0}" -gt 1 ]; then
        echo "  ============================================================"
        echo "  ⚠ DIVERGENCE: ${distinct} conflicting chain heads detected!"
        for i in $(seq 0 $((NNODES - 1))); do
            h="$(head_of "$i")"
            [ -n "$h" ] && [ "$h" != "-" ] && printf '    conflict: node-%d holds %s\n' "$i" "$h"
        done
        echo "    (reconciliation/finality must converge these — see logs)"
        echo "  ============================================================"
    fi
}

wait_finality() { # $1=min finalized length, $2=timeout-seconds
    local min="${1:-33}" t="${2:-900}" waited=0 best f i
    log "waiting for finalized >= ${min} (timeout ${t}s)"
    while [ "$waited" -lt "$t" ]; do
        best=0
        for i in $(seq 0 $((NNODES - 1))); do
            f="$(fin_of "$i")"
            if [ "${f:-0}" -gt "$best" ] 2>/dev/null; then best="$f"; fi
        done
        if [ "$best" -ge "$min" ]; then log "finality OK: ${best}"; return 0; fi
        if [ $((waited % 60)) -eq 0 ]; then
            local tips="" j
            for j in $(seq 0 $((NNODES - 1))); do tips="${tips}$(cl_of "$j")/"; done
            log "  finalized=${best} cl=${tips%/} (${waited}s)"
        fi
        sleep 10; waited=$((waited + 10))
    done
    die "finality did not reach ${min} in ${t}s"
}

cmd_verify() { # reachable nodes must agree on the finalized root and see full set
    local roots="" i f v expected="${EXPECTED:-${NNODES}}" reachable=0 down=0
    # node_down: true when the API is unreachable or answers garbage — a
    # stopped/left node returns "-" from valcount_of, NOT 0, so the old
    # [ "$v" = 0 ] check let dead nodes through and their empty roots were
    # counted as a divergent checkpoint.
    node_down() {
        local vv="$(valcount_of "$1")"
        [ -z "$vv" ] || [ "$vv" = "-" ] || [ "$vv" = "0" ] || \
            ! curl -s -m 2 -o /dev/null "http://127.0.0.1:$((8281 + $1))/"
    }
    for i in $(seq 0 $((NNODES - 1))); do
        if node_down "$i"; then down=$((down+1)); else reachable=$((reachable+1)); fi
    done
    local eff=$expected
    [ "$reachable" -lt "$expected" ] && eff=$reachable
    for i in $(seq 0 $((NNODES - 1))); do
        node_down "$i" && { log "node-${i} unreachable — skipped (left?)"; continue; }
        v="$(valcount_of "$i")"
        [ "$v" -lt "$eff" ] && die "node-${i} has ${v}/${eff} validators"
        f="$(curl -s -m 5 -X POST "http://127.0.0.1:$((8281 + i))/getChainNumber" -H 'Content-Type: application/json' -d '{}' |
            python3 -c 'import sys,json; d=json.load(sys.stdin); f=d.get("finalizedBlockHash") or ""; print((f["bytes"] if isinstance(f,dict) else str(f)).strip())' 2>/dev/null || echo none)"
        [ -z "$f" ] || [ "$f" = "none" ] && { down=$((down+1)); log "node-${i} gave no finalized root — skipped"; continue; }
        roots="${roots}${f}\n"
    done
    local n; n=$(printf '%b' "$roots" | sort -u | grep -c . || true)
    [ "$n" -gt 1 ] && { printf '%b' "$roots" >&2; die "divergent finalized checkpoints"; }
    log "verify OK: ${eff} validators on each of ${reachable} reachable node(s), one finalized root ($([ $down -gt 0 ] && echo "${down} left/down" || echo "all up"))"
}

cmd_transfer() {
    local from=0 to=1 ph addr attempt bal
    ph="$(grep '^PUBKEY_HASH=' "${WORKDIR}/testwallet.env" | cut -d= -f2-)"
    addr="$(grep '^ADDRESS=' "${WORKDIR}/testwallet.env" | cut -d= -f2-)"
    # The /fundAddresses faucet has been removed. Staked validators' coins are
    # BONDED (locked), so the transfer source is the dedicated non-staked TEST
    # wallet funded at genesis (GENESIS_CSV) — its UTXOs are confirmed chain
    # state on every node. Just wait for them, then submit ONE real transfer.
    sleep 5
    local seed rph
    seed="$(grep '^POS_VALIDATOR_KEY=' "${WORKDIR}/testwallet.env" | cut -d= -f2-)"
    rph="$(grep '^PUBKEY_HASH=' "${WORKDIR}/node-${to}/validator.env" | cut -d= -f2-)"
    sign_exit_for_cp
    java -cp "${WORKDIR}/cp/BOOT-INF/classes:${WORKDIR}/cp/BOOT-INF/lib/*" \
        "${VALSRC}/TransferOnce.java" "$seed" "$rph" 50000 "http://127.0.0.1:$((8281 + from))/" \
        "${WORKDIR}/walletctx-transfer" || die "transfer tx build/submit failed"
    for attempt in 1 2 3 4 5 6 7 8 9; do
        sleep 10
        bal=999999999
        for i in $(seq 0 $((NNODES - 1))); do
            # skip a node that was intentionally stopped (leave test)
            docker inspect "bt4-node-node-${i}-server" >/dev/null 2>&1 || continue
            bal=$(api "$i" /getBalances "[\"${rph}\"]" | python3 -c '
import sys, json, base64
try: d=json.load(sys.stdin)
except Exception: print(0); sys.exit(0)
t=0
for u in d.get("outputs") or []:
    v=(u or {}).get("value") or {}
    if isinstance(v,dict) and base64.b64decode(v.get("tokenid") or "")==b"\xbc": t+=int(v.get("value") or 0)
print(t)')
            [ "${bal:-0}" -ge 50000 ] || break
        done
        [ "${bal:-0}" -ge 50000 ] && { log "transfer visible on all running nodes"; return 0; }
    done
    die "transfer not visible everywhere (kafka/sync broken)"
}

sign_exit_for_cp() { # ensure the exec-jar classpath is unpacked for tool runs
    local cpdir="${WORKDIR}/cp"
    if [ ! -d "${cpdir}/BOOT-INF/classes" ]; then
        mkdir -p "${cpdir}"
        local jar; jar="$(ls -t "${ROOT}"/layer0-server/target/layer0-server-*-exec.jar | head -1)"
        unzip -oq "$jar" 'BOOT-INF/*' -d "${cpdir}"
    fi
}

sign_exit_for() { # $1=index, $2=nonce → PUBKEY=/SIGNATURE= lines
    sign_exit_for_cp
    java -cp "${WORKDIR}/cp/BOOT-INF/classes:${WORKDIR}/cp/BOOT-INF/lib/*" \
        "${VALSRC}/SignExit.java" \
        "$(grep '^POS_VALIDATOR_KEY=' "${WORKDIR}/node-$1/validator.env" | cut -d= -f2-)" "$2"
}

seed_env_drop() { # $1=varname $2=port — remove host:port from a seed list in common.env
    python3 - "${WORKDIR}/common.env" "$1" "$2" <<'PYEOF'
import re, sys
path, var, port = sys.argv[1], sys.argv[2], sys.argv[3]
s = open(path).read()
m = re.search(rf'^{var}="([^"]*)"$', s, re.M)
if m:
    kept = [x for x in m.group(1).split(',') if x and not x.endswith(':' + port)]
    s = re.sub(rf'^{var}="[^"]*"$', f'{var}="{",".join(kept)}"', s, count=1, flags=re.M)
    open(path, 'w').write(s)
PYEOF
}

cmd_leave() { # $1=index — signed BLOCKTYPE_EXIT, stop it, drop from seeds
    local i="$1" out pub sig resp ok=0 nonce
    [ -n "$i" ] || die "leave N"
    # The exit signature binds to the node's max-confirmed chainLength at
    # submission time; retry when the chain advances in between.
    for _ in $(seq 1 6); do
        nonce="$(cl_of "$i")"
        if [ -z "${nonce:-}" ] || [ "$nonce" = "-" ]; then sleep 5; continue; fi
        out="$(sign_exit_for "$i" "$nonce")" || { sleep 5; continue; }
        pub="$(printf '%s\n' "$out" | grep '^PUBKEY=' | cut -d= -f2-)"
        sig="$(printf '%s\n' "$out" | grep '^SIGNATURE=' | cut -d= -f2-)"
        [ -n "$sig" ] || { sleep 5; continue; }
        resp="$(api "$i" /requestValidatorExit "{\"pubkey\":\"${pub}\",\"signature\":\"${sig}\"}")"
        printf '%s' "$resp" | grep -q '"errorcode" : 0' && { ok=1; break; }
        log "exit rejected — retrying ($(printf '%s' "$resp" | tr '\n' ' ' | head -c 120))"
        sleep 8
    done
    [ "$ok" = 1 ] || die "requestValidatorExit failed for node-${i}"
    # STAMP the exit: cmd_join refuses re-keying until this cl + 2 epochs is
    # confirmed (else old deposit + fresh deposit BOTH stay active — measured
    # validators=5->6->7 voting-weight inflation across join-storm cycles).
    cl_of "$i" > "${WORKDIR}/node-${i}.leave_stamp" 2>/dev/null || true
    # Poll for the deposit ACTUALLY dropping out of the validator set instead
    # of a blind 2-epoch sleep: inclusion takes a slot and finalization needs
    # ~2 more epochs, so allow 3 — and fail loudly here rather than letting a
    # still-active old deposit inflate the validator set at join time.
    local waited=0 timeout=$(( SLOT_MS * ${POS_SLOTS_PER_EPOCH:-8} * 4 / 1000 )) vc
    log "exit accepted; waiting for the BLOCKTYPE_EXIT to finalize (validator set ${NNODES} -> $((NNODES - 1)))"
    while [ "$waited" -lt "$timeout" ]; do
        vc="$(valcount_of "$i")"
        [ -n "$vc" ] && [ "$vc" != "-" ] && [ "$vc" -lt "$NNODES" ] 2>/dev/null && \
            { log "old deposit left the validator set after ${waited}s"; break; }
        sleep 15; waited=$((waited + 15))
        [ $((waited % 60)) -eq 0 ] && log "  validators=${vc:--} (${waited}s)"
    done
    vc="$(valcount_of "$i")"
    [ -n "$vc" ] && [ "$vc" != "-" ] && [ "$vc" -ge "$NNODES" ] 2>/dev/null && \
        die "old deposit still active after ${timeout}s (validators=${vc}) — join would inflate the set"
    docker rm -f "bt4-node-node-${i}-server" >/dev/null 2>&1 || true
    seed_env_drop SEED_HOSTS "$((8281 + i))"
    seed_env_drop GOSSIP_SEEDS "$((9421 + i))"
    log "node-${i} exited: container stopped, seeds updated"
}

cmd_join() { # $1=index — fresh keys, seed, start, stake
    local i="$1"
    # ENFORCEMENT: a fresh-key join re-activates voting weight. If the OLD
    # deposit's exit has not finalized, old + new BOTH count — an operator
    # shortcut (kill instead of finalized leave) silently inflates the
    # validator set (audited: validators 5->6->7 across join-storm rounds).
    local stamp="${WORKDIR}/node-${i}.leave_stamp"
    if [ -f "$stamp" ]; then
        local left_cl now_cl need
        left_cl=$(cat "$stamp" | tr -dc '0-9')
        # query ANY reachable node (the joining node may be the one queried
        # otherwise — e.g. NNODES=1 or node-0 churn — and answer garbage)
        now_cl=""
        for _j in $(seq 0 $((NNODES - 1))); do
            now_cl=$(cl_of "$_j")
            [ -n "${now_cl:-}" ] && [ "${now_cl:-0}" -gt 0 ] 2>/dev/null && break
            now_cl=""
        done
        need=$(( left_cl + 2 * ${POS_SLOTS_PER_EPOCH:-8} ))
        # The exit must finalize (cl advances ~2 epochs) before a fresh-key
        # join reactivates voting weight; otherwise old + new deposits both
        # count. Wait it out instead of requiring a manual re-run (the `all`
        # flow calls join right after leave).
        if [ "${now_cl:-0}" -lt "$need" ] && [ "${JOIN_FORCE:-0}" != "1" ]; then
            local waited=0
            log "join node-${i}: waiting for exit finalization (cl=${now_cl:-0}, need>=$need)..."
            while [ "$waited" -lt 900 ] && [ "${now_cl:-0}" -lt "$need" ]; do
                sleep 15; waited=$((waited + 15))
                now_cl=""
                for _j in $(seq 0 $((NNODES - 1))); do
                    now_cl=$(cl_of "$_j")
                    [ -n "${now_cl:-0}" ] && [ "${now_cl:-0}" -gt 0 ] 2>/dev/null && break
                    now_cl=""
                done
            done
            [ "${now_cl:-0}" -ge "$need" ] ||
                die "join node-${i}: exit not finalized after ${waited}s (cl=${now_cl:-0}, need>=$need) — JOIN_FORCE=1 to override"
        fi
        rm -f "$stamp"
    elif [ -d "${WORKDIR}/node-${i}.old" ] || docker inspect "bt4-node-node-${i}-server" >/dev/null 2>&1; then
        # JOIN_FORCE also covers a consumed stamp with a stale node dir: the
        # previous join attempt may have died AFTER consuming the stamp
        # (e.g. at the sync gate), leaving no way back without this escape.
        [ "${JOIN_FORCE:-0}" = "1" ] || die "join node-${i} blocked: no finalized leave stamp (run 'leave ${i}' first, or JOIN_FORCE=1)"
        log "join node-${i}: JOIN_FORCE — overriding missing leave stamp"
    fi
    rm -rf "${WORKDIR}/node-${i}.old" && mv "${WORKDIR}/node-${i}" "${WORKDIR}/node-${i}.old" 2>/dev/null || true
    mkdir -p "${WORKDIR}/node-${i}"; make_node_env "$i"
    docker exec ${PGCONT} psql -p ${PGINNER:-5432} -U root -d postgres -c "DROP DATABASE IF EXISTS bt4_${i};" >/dev/null
    docker exec ${PGCONT} psql -p ${PGINNER:-5432} -U root -d postgres -c "CREATE DATABASE bt4_${i};" >/dev/null
    ( cd "${WORKDIR}/node-${i}" && bash -c "
        set -euo pipefail
        source ../common.env; source ./validator.env
        source ../validator_common.sh
        start_server" ) > "${WORKDIR}/node-${i}/start.log" 2>&1 &
    sleep 5
    # The joining validator has NO genesis funding (the chain is already live),
    # so fund it from the dedicated non-staked TEST wallet (its funds are not
    # bonded) via a real signed transfer, then stake it.
    local funder seed2 rph2
    for funder in $(seq 0 $((NNODES - 1))); do
        docker inspect "bt4-node-node-${funder}-server" >/dev/null 2>&1 && break
    done
    seed2="$(grep '^POS_VALIDATOR_KEY=' "${WORKDIR}/testwallet.env" | cut -d= -f2-)"
    rph2="$(grep '^PUBKEY_HASH=' "${WORKDIR}/node-${i}/validator.env" | cut -d= -f2-)"
    sign_exit_for_cp
    java -cp "${WORKDIR}/cp/BOOT-INF/classes:${WORKDIR}/cp/BOOT-INF/lib/*" \
        "${VALSRC}/TransferOnce.java" "$seed2" "$rph2" "${JOIN_FUND:-40000000}" \
        "http://127.0.0.1:$((8281 + funder))/" "${WORKDIR}/walletctx-join" \
        || die "join funding transfer for node-${i} failed"
    phase_stake_one "$i" >&2
    log "node-${i} rejoined with fresh identity"
}

cmd_down() {
    for i in $(seq 0 $((NNODES - 1))); do docker rm -f "bt4-node-node-${i}-server" >/dev/null 2>&1 || true; done
    for i in $(seq 0 $((NNODES - 1))); do
        docker exec ${PGCONT} psql -p ${PGINNER:-5432} -U root -d postgres -c "DROP DATABASE IF EXISTS bt4_${i};" >/dev/null 2>&1 || true
    done
    log "down"
}

cmd_all() {
    local last=$((NNODES - 1))
    cmd_up
    cmd_stake
    wait_finality 33 2400
    cmd_status
    cmd_transfer
    log "=== leave ${last} ==="
    cmd_leave "$last"
    sleep 30
    EXPECTED=$((NNODES - 1)) cmd_verify
    log "=== join ${last} (fresh keys) ==="
    cmd_join "$last"
    wait_finality 40 2400
    EXPECTED=${NNODES} cmd_verify
    cmd_status
    log "ALL PASSED"
}

case "${1:-}" in
    up) shift; cmd_up "$@" ;;
    stake) shift; cmd_stake "$@" ;;
    status) shift; cmd_status ;;
    verify) shift; cmd_verify "$@" ;;
    finality) shift; wait_finality "$@" ;;
    transfer) shift; cmd_transfer ;;
    leave) [ $# -ge 2 ] || die "leave N"; cmd_leave "$2" ;;
    join) [ $# -ge 2 ] || die "join N"; cmd_join "$2" ;;
    down) shift; cmd_down ;;
    all) shift; cmd_all "$@" ;;
    *) sed -n '2,25p' "$0" | sed 's/^# \{0,1\}//'; exit 1 ;;
esac
