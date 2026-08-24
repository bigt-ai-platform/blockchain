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
# SLOT_MS (pos.slotIntervalMs, default 2000 — small epochs for fast finality),
# READINESS_MIN (bigtangle.readinessTimeoutMinutes, default 10),
# XMX (per-node JVM heap, default 3g — 3 nodes ≈ 11G RSS, sized for a 16G host;
#   lower it on smaller machines, e.g. XMX=1200m testnodes.sh up).
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
VALSRC="${ROOT}/helper/prod/validators"
WORKDIR="${WORKDIR:-/tmp/bt4test}"
IMAGE="${IMAGE:-ghcr.io/bigt-ai-platform/layer0-server:latest}"
PGPORT="${PGPORT:-21532}"
PGDATA_ROOT="${PGDATA_ROOT:-/data/vm/test-bigtangle-postgres/var/lib/postgresql/data}"
KAFKA="${KAFKA:-localhost:9092}"
SLOT_MS="${SLOT_MS:-2000}"
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
            -e POSTGRES_PASSWORD=test1234 -e POSTGRES_DB=info postgres:16 >/dev/null
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
            rm -rf "${dir}/BOOT-INF"; unzip -oq "$jar" 'BOOT-INF/*' -d "$dir"; touch "${dir}/.stamp"
        fi
        java -cp "${dir}/BOOT-INF/classes:${dir}/BOOT-INF/lib/*" net.bigtangle.tools.ValidatorKeyTool generate
    else
        docker run --rm --network none --entrypoint java "$IMAGE" \
            -cp /app/app.jar net.bigtangle.tools.ValidatorKeyTool generate
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
JAVA_OPTS_SERVER="-Xmx${XMX} -Dbigtangle.readinessTimeoutMinutes=${READINESS_MIN} -Dpos.slotIntervalMs=${SLOT_MS} -Dnet.bigtangle.pos.attestationActivation=1"
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
FUND_MODE=bootstrap
FUND_ENABLED=true
GENESIS_CSV=
POS_SLOTS_PER_EPOCH=32
JAVA_OPTS_SERVER="-Xmx${XMX} -Dnet.bigtangle.pos.attestationActivation=1"
EOF
    # the test drives the REAL shared machinery
    cp "${VALSRC}/validator_common.sh" "${WORKDIR}/validator_common.sh"
    for i in $(seq 0 $((NNODES - 1))); do
        rm -rf "${WORKDIR}/node-${i}"; mkdir -p "${WORKDIR}/node-${i}"
        make_node_env "$i"
    done
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
    local ok=0
    for i in $(seq 0 $((NNODES - 1))); do
        for _ in $(seq 1 100); do
            [ "$(curl -s -m 2 -o /dev/null -w '%{http_code}' "http://127.0.0.1:$((8281 + i))/" 2>/dev/null)" = "200" ] && { ok=$((ok+1)); break; }
            sleep 3
        done
    done
    [ "$ok" = "$NNODES" ] || die "only ${ok}/${NNODES} APIs up — see ${WORKDIR}/node-*/start.log"
    log "up: ${NNODES} nodes serving"
}

phase_stake_one() { # $1=index
    ( cd "${WORKDIR}/node-$1" && bash -c "
        set -euo pipefail
        source ../common.env; source ./validator.env
        source ../validator_common.sh
        wait_api
        if [ \"\$(balance_big)\" -lt \"\${STAKE_AMOUNT}\" ]; then fund_validator; fi
        wait_balance \${STAKE_AMOUNT}
        stake_validator >/dev/null; sleep 2; activate_validator >/dev/null" )
}

cmd_stake() {
    for i in $(seq 0 $((NNODES - 1))); do
        log "staking node-${i}"
        phase_stake_one "$i" >&2
    done
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
    for i in $(seq 0 $((NNODES - 1))); do
        v="$(valcount_of "$i")"
        [ "${v:-0}" = "0" ] && ! curl -s -m 2 -o /dev/null "http://127.0.0.1:$((8281 + i))/" \
            && { down=$((down+1)); continue; }
        reachable=$((reachable+1))
    done
    local eff=$expected
    [ "$reachable" -lt "$expected" ] && eff=$reachable
    for i in $(seq 0 $((NNODES - 1))); do
        v="$(valcount_of "$i")"
        [ "${v:-0}" = "0" ] && { log "node-${i} unreachable — skipped (left?)"; continue; }
        [ "$v" -lt "$eff" ] && die "node-${i} has ${v}/${eff} validators"
        f="$(curl -s -m 5 -X POST "http://127.0.0.1:$((8281 + i))/getChainNumber" -H 'Content-Type: application/json' -d '{}' |
            python3 -c 'import sys,json; d=json.load(sys.stdin); f=d.get("finalizedBlockHash") or ""; print((f["bytes"] if isinstance(f,dict) else str(f)).strip())' 2>/dev/null || echo none)"
        roots="${roots}${f}\n"
    done
    local n; n=$(printf '%b' "$roots" | sort -u | grep -c . || true)
    [ "$n" -gt 1 ] && { printf '%b' "$roots" >&2; die "divergent finalized checkpoints"; }
    log "verify OK: ${eff} validators on each of ${reachable} reachable node(s), one finalized root ($([ $down -gt 0 ] && echo "${down} left/down" || echo "all up"))"
}

cmd_transfer() {
    local from=0 ph attempt bal
    ph="$(grep '^PUBKEY_HASH=' "${WORKDIR}/node-${from}/validator.env" | cut -d= -f2-)"
    api "$from" /fundAddresses "{\"addresses\":[{\"address\":\"${ph}\",\"value\":100000,\"index\":$((700000000 + RANDOM))}]}" >/dev/null
    for attempt in 1 2 3 4 5 6; do
        sleep 15
        bal=999999999
        for i in $(seq 0 $((NNODES - 1))); do
            # skip a node that was intentionally stopped (leave test)
            docker inspect "bt4-node-node-${i}-server" >/dev/null 2>&1 || continue
            bal=$(api "$i" /getBalances "[\"${ph}\"]" | python3 -c '
import sys, json, base64
try: d=json.load(sys.stdin)
except Exception: print(0); sys.exit(0)
t=0
for u in d.get("outputs") or []:
    v=(u or {}).get("value") or {}
    if isinstance(v,dict) and base64.b64decode(v.get("tokenid") or "")==b"\xbc": t+=int(v.get("value") or 0)
print(t)')
            [ "${bal:-0}" -ge 100000 ] || break
        done
        [ "${bal:-0}" -ge 100000 ] && { log "transfer visible on all running nodes"; return 0; }
    done
    die "transfer not visible everywhere (kafka/sync broken)"
}

sign_exit_for() { # $1=index, $2=nonce → PUBKEY=/SIGNATURE= lines
    local cpdir="${WORKDIR}/cp"
    if [ ! -d "${cpdir}/BOOT-INF/classes" ]; then
        mkdir -p "${cpdir}"
        local jar; jar="$(ls -t "${ROOT}"/layer0-server/target/layer0-server-*-exec.jar | head -1)"
        unzip -oq "$jar" 'BOOT-INF/*' -d "${cpdir}"
    fi
    java -cp "${cpdir}/BOOT-INF/classes:${cpdir}/BOOT-INF/lib/*" \
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
    log "exit accepted; waiting ~2 epochs for the BLOCKTYPE_EXIT to finalize"
    sleep $(( SLOT_MS * ${POS_SLOTS_PER_EPOCH:-32} * 2 / 1000 ))
    docker rm -f "bt4-node-node-${i}-server" >/dev/null 2>&1 || true
    seed_env_drop SEED_HOSTS "$((8281 + i))"
    seed_env_drop GOSSIP_SEEDS "$((9421 + i))"
    log "node-${i} exited: container stopped, seeds updated"
}

cmd_join() { # $1=index — fresh keys, seed, start, stake
    local i="$1"
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
