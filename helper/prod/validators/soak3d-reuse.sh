#!/usr/bin/env bash
# soak3d-reuse.sh — drive a 3-day stability + attack soak on an ALREADY-RUNNING
# 3-node testnodes mesh (no down/up/stake/finality — the mesh is up and funded).
#
# Sequence:
#   1. baseline benchmark (MeshBm 150 tx)
#   2. 72-hour soak: hourly MeshBm waves; every 6h ALSO MeshAttack V1-V8
#   3. meshmon health sampler in background
#   4. "last": leave 2 -> verify(2) -> join 2 -> finality -> verify(3) -> status
#
# Reuses soak3d.sh's loop logic but against the live mesh on ports 8281..8283.
# Env overrides: IMAGE (for leave/join node container), BENCH_WALLETS/ATTACK_WALLETS
# only for wallet-index arithmetic, WAVE_SIZE, CLIENTS, BATCH, SOAK_HOURS,
# ATTACK_EVERY, ATTACK_FIRST, SOAK_START, WORKDIR, NNODES.
set -u

ROOT="$(cd "$(dirname "$0")/../../.." && pwd)"
S="${ROOT}/helper/prod/testnodes.sh"
VALSRC="${ROOT}/helper/prod/validators"
WORKDIR="${WORKDIR:-/tmp/bt4test}"
NNODES="${NNODES:-3}"
IMAGE="${IMAGE:-layer0-server:soak5}"

BENCH_WALLETS="${BENCH_WALLETS:-51000}"
ATTACK_WALLETS="${ATTACK_WALLETS:-2000}"
BENCH_FUND="${BENCH_FUND:-50000}"
ATTACK_FUND="${ATTACK_FUND:-30000}"
XMX="${XMX:-2g}"
PER_NODE_PG="${PER_NODE_PG:-1}"

WAVE_SIZE="${WAVE_SIZE:-150}"
CLIENTS="${CLIENTS:-4}"
BATCH="${BATCH:-20}"
SOAK_START="${SOAK_START:-40011}"
BASELINE_START="${BASELINE_START:-10011}"
SOAK_HOURS="${SOAK_HOURS:-72}"
ATTACK_FIRST="${ATTACK_FIRST:-0}"
ATTACK_EVERY="${ATTACK_EVERY:-6}"
CONFIRM_TIMEOUT="${CONFIRM_TIMEOUT:-1800}"

# Attack wallet index base = NNODES+1+BENCH_WALLETS (matches testnodes.sh).
ATTACK_START=$((NNODES + 1 + BENCH_WALLETS))

LOG=/tmp/opencode/soak3d-reuse.log
REPORT="${WORKDIR}/soak3d_reuse_report.tsv"
SOAK_LOG="${WORKDIR}/soak3d_reuse_waves.log"
ATTACK_LOG="${WORKDIR}/soak3d_reuse_attacks.log"
MESHMON_TSV="${WORKDIR}/soak3d_reuse_meshmon.tsv"

log()  { echo "[soak3d-reuse $(date -u +%F\ %H:%M:%S)] $*"; }

CP_DIR="${WORKDIR}/cp"
[ -d "${CP_DIR}/BOOT-INF/classes" ] || { mkdir -p "${CP_DIR}"; jar="$(ls -t "${ROOT}"/layer0-server/target/layer0-server-*-exec.jar | head -1)"; unzip -oq "$jar" 'BOOT-INF/*' -d "${CP_DIR}"; }
CP="${CP_DIR}/BOOT-INF/classes:${CP_DIR}/BOOT-INF/lib/*"

J=/tmp/opencode/jdk25/bin/java
[ -x "$J" ] || J=java

{
echo "=========== soak3d-reuse START $(date -u +%F\ %H:%M:%S) ==========="
echo "reusing running mesh: nnodes=${NNODES} image=${IMAGE} bench_wallets=${BENCH_WALLETS}"
echo "wave_size=${WAVE_SIZE} clients=${CLIENTS} batch=${BATCH} soak_hours=${SOAK_HOURS} attack_every=${ATTACK_EVERY}"
echo "soak_start=${SOAK_START} attack_start=${ATTACK_START} report=${REPORT}"
} >> "${LOG}" 2>&1

# ---- 1. baseline benchmark (against the live mesh) -------------------------
log "baseline benchmark: ${WAVE_SIZE} tx wave from index ${BASELINE_START}"
"$J" -Dbench.fund="${BENCH_FUND}" -Dbench.pay=40000 -Dbench.recvIdx=99999 \
    -Dbench.confirm=1 -Dbench.confirmNode=0 -Dbench.confirmTimeoutSec="${CONFIRM_TIMEOUT}" \
    -Dbench.progressSec=30 -Dbench.rate=0 \
    -cp "${CP}" "${VALSRC}/MeshBm.java" run \
    "${BASELINE_START}" "${WAVE_SIZE}" "${CLIENTS}" "${BATCH}" "${NNODES}" "http://127.0.0.1:" >> "${SOAK_LOG}" 2>&1
log "baseline benchmark done (see ${SOAK_LOG})"

# ---- 2. background meshmon -------------------------------------------------
nohup env WORKDIR="${WORKDIR}" NNODES="${NNODES}" \
    bash "${VALSRC}/meshmon.sh" 60 "${MESHMON_TSV}" "${NNODES}" > "${WORKDIR}/soak3d_reuse_meshmon.log" 2>&1 &
MESHMON_PID=$!
log "meshmon pid ${MESHMON_PID} -> ${MESHMON_TSV}"

# ---- 3. soak: hourly waves + periodic attacks --------------------------------
{
echo -e "hour\twaveStart\tsize\tsubmitted\tdropped\tconfirmed\tsubmitTps\tconfirmTps\tpeakTps\tp50Ms\tp95Ms\tp99Ms\tattackResult"
for h in $(seq 0 $((SOAK_HOURS - 1))); do
    start=$((SOAK_START + h * WAVE_SIZE))
    attackResult="skip"
    if [ $((h % ATTACK_EVERY)) -eq 0 ] && [ "$h" -ge "${ATTACK_FIRST}" ]; then
        log "hour ${h}: running MeshAttack V1-V8 (attack_start=${ATTACK_START})"
        attackResult="FAIL"
        if "$J" -Dattack.fund="${ATTACK_FUND}" -Dattack.pay=20000 \
            -Dattack.confirmTimeoutSec=300 \
            -cp "${CP}" "${VALSRC}/MeshAttack.java" run \
            "${ATTACK_START}" 400 "${NNODES}" "http://127.0.0.1:" 1.0 \
            > "${ATTACK_LOG}.${h}" 2>&1; then
            attackResult="PASS"
        fi
        grep -E "\[DEFLECTED\]|\[BREACH\]|ALL_ATTACKS_DEFLECTED|ATTACK_BREACH" "${ATTACK_LOG}.${h}" >> "${LOG}" 2>&1
        tail -12 "${ATTACK_LOG}.${h}" >> "${LOG}" 2>&1
        log "hour ${h}: MeshAttack result=${attackResult}"
        ATTACK_START=$((ATTACK_START + 120))
    fi

    # Mesh-health gate: abort only after 2 CONSECUTIVE divergent samples.
    split=0
    for i in $(seq 0 $((NNODES - 1))); do
        head="$(curl -s -m 5 -X POST "http://127.0.0.1:$((8281 + i))/getChainNumber" -H 'Content-Type: application/json' -d '{}' 2>/dev/null \
            | python3 -c "import sys,json,base64
try:
    d=json.load(sys.stdin); r=d.get('txReward'); r=json.loads(r) if isinstance(r,str) else (r or {})
    b=(r.get('blockHash') or {}).get('bytes'); print(base64.b64decode(b).hex()[:16] if b else '-')
except Exception: print('-')" 2>/dev/null)"
        [ -n "$head" ] && [ "$head" != "-" ] && echo "$head" >> "${WORKDIR}/.heads.${head}"
    done
    nheads=$(cat "${WORKDIR}"/.heads.* 2>/dev/null | sort -u | grep -c . || true)
    rm -f "${WORKDIR}"/.heads.*
    if [ "${nheads:-0}" -gt 1 ]; then
        streak_file="${WORKDIR}/.soak_reuse_split_streak"
        streak=$([ -f "$streak_file" ] && cat "$streak_file" || echo 0)
        streak=$((streak + 1))
        echo "$streak" > "$streak_file"
        log "!!! MESH SPLIT SAMPLE ${streak}/2 at hour ${h}: ${nheads} distinct confirmed heads"
        [ "$streak" -ge 2 ] && split=1
    else
        rm -f "${WORKDIR}/.soak_reuse_split_streak"
    fi

    log "hour ${h}: MeshBm wave ${WAVE_SIZE} tx from index ${start} (clients=${CLIENTS} batch=${BATCH})"
    result=$("$J" \
        -Dbench.fund="${BENCH_FUND}" -Dbench.pay=40000 \
        -Dbench.recvIdx="${h}" -Dbench.confirm=1 -Dbench.confirmNode=0 \
        -Dbench.confirmTimeoutSec="${CONFIRM_TIMEOUT}" -Dbench.progressSec=30 \
        -Dbench.rate=0 \
        -cp "${CP}" "${VALSRC}/MeshBm.java" run \
        "${start}" "${WAVE_SIZE}" "${CLIENTS}" "${BATCH}" "${NNODES}" "http://127.0.0.1:" 2>&1)
    echo "$result" >> "${SOAK_LOG}"
    sub=$(echo "$result" | grep -oP 'SUBMITTED=\K[0-9]+' | head -1)
    drop=$(echo "$result" | grep -oP 'DROPPED=\K[0-9]+' | head -1)
    conf=$(echo "$result" | grep -oP 'CONFIRMED=\K[0-9]+' | head -1)
    stps=$(echo "$result" | grep -oP 'SUBMIT_TPS=\K[0-9.]+' | head -1)
    ctps=$(echo "$result" | grep -oP 'CONFIRM_TPS=\K[0-9.]+' | head -1)
    ptps=$(echo "$result" | grep -oP 'PEAK_TPS=\K[0-9.]+' | head -1)
    p50=$(echo "$result" | grep -oP 'P50_MS=\K[0-9]+' | head -1)
    p95=$(echo "$result" | grep -oP 'P95_MS=\K[0-9]+' | head -1)
    p99=$(echo "$result" | grep -oP 'P99_MS=\K[0-9]+' | head -1)
    printf '%d\t%d\t%d\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' \
        "$h" "$start" "${WAVE_SIZE}" "${sub:-0}" "${drop:-0}" "${conf:-0}" \
        "${stps:-0}" "${ctps:-0}" "${ptps:-0}" "${p50:-0}" "${p95:-0}" "${p99:-0}" "${attackResult}"
    log "hour ${h} done: submitted=${sub:-0} confirmed=${conf:-0} submitTps=${stps:-0} confirmTps=${ctps:-0} attack=${attackResult}"
    [ "$split" = 1 ] && { log "SOAK ABORTED: mesh split detected at hour ${h}"; echo "SOAK_ABORTED_MESH_SPLIT" >> "${LOG}" 2>&1; kill "${MESHMON_PID}" 2>/dev/null || true; exit 2; }
    if [ "$h" -lt $((SOAK_HOURS - 1)) ]; then
        sleep 3600
    fi
done
} > "${REPORT}" 2>&1
log "soak waves finished — report ${REPORT}"

# ---- 4. LAST: leave/join + final verify ------------------------------------
log "=== LAST: leave 2 ==="
env IMAGE="${IMAGE}" XMX="${XMX}" PER_NODE_PG="${PER_NODE_PG}" bash "${S}" leave 2 >> "${LOG}" 2>&1 || { log "FAIL leave"; exit 1; }
sleep 30
env IMAGE="${IMAGE}" XMX="${XMX}" PER_NODE_PG="${PER_NODE_PG}" EXPECTED=$((NNODES - 1)) bash "${S}" verify >> "${LOG}" 2>&1 || { log "FAIL verify(after leave)"; exit 1; }
log "=== LAST: join 2 (fresh keys) ==="
env IMAGE="${IMAGE}" XMX="${XMX}" PER_NODE_PG="${PER_NODE_PG}" bash "${S}" join 2 >> "${LOG}" 2>&1 || { log "FAIL join"; exit 1; }
log "finality >= 40"
env IMAGE="${IMAGE}" XMX="${XMX}" PER_NODE_PG="${PER_NODE_PG}" bash "${S}" finality 40 2400 >> "${LOG}" 2>&1 || { log "FAIL finality(after join)"; exit 1; }
env IMAGE="${IMAGE}" XMX="${XMX}" PER_NODE_PG="${PER_NODE_PG}" EXPECTED="${NNODES}" bash "${S}" verify >> "${LOG}" 2>&1 || { log "FAIL final verify"; exit 1; }
log "=== LAST: final status ==="
env IMAGE="${IMAGE}" XMX="${XMX}" PER_NODE_PG="${PER_NODE_PG}" bash "${S}" status >> "${LOG}" 2>&1

kill "${MESHMON_PID}" 2>/dev/null || true
echo "=========== soak3d-reuse DONE $(date -u +%F\ %H:%M:%S) ===========" >> "${LOG}" 2>&1
echo "SOAK3D_REUSE_COMPLETE"
exit 0
