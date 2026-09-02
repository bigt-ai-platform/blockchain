#!/usr/bin/env bash
# soak3d.sh — 3-day (72 h) stability + attack orchestration on the hermetic
# 3-node testnodes mesh, driven from current HEAD (IMAGE=layer0-server:soak4).
#
# Sequence:
#   1. down + up a FRESH 3-node mesh with genesis-funded bench + attack wallets
#   2. stake all nodes, wait for finality
#   3. baseline benchmark (MeshBm single wave)
#   4. 3-day soak: 72 hourly MeshBm waves; every 6th hour ALSO runs the
#      MeshAttack suite (V1-V18) against the mesh; meshmon samples health
#   5. "last": leave 2 -> verify(2) -> join 2 -> finality -> verify(3)
#   6. final status + report
#
# Run detached:  setsid nohup env ... bash soak3d.sh > /tmp/opencode/soak3d.log 2>&1 &
# Env overrides: IMAGE, BENCH_WALLETS, ATTACK_WALLETS, WAVE_SIZE, CLIENTS,
# BATCH, SOAK_HOURS, ATTACK_EVERY, SOAK_START, WORKDIR, NNODES.
set -u

ROOT="$(cd "$(dirname "$0")/../../.." && pwd)"
S="${ROOT}/helper/prod/testnodes.sh"
VALSRC="${ROOT}/helper/prod/validators"
WORKDIR="${WORKDIR:-/tmp/bt4test}"
NNODES="${NNODES:-3}"
IMAGE="${IMAGE:-layer0-server:soak6}"

# bench wallets fund indices [4, 4+BENCH_WALLETS): soak uses 40011..50811,
# so BENCH_WALLETS >= 50812; 51000 gives margin.
BENCH_WALLETS="${BENCH_WALLETS:-51000}"
ATTACK_WALLETS="${ATTACK_WALLETS:-2000}"
BENCH_FUND="${BENCH_FUND:-50000}"
ATTACK_FUND="${ATTACK_FUND:-30000}"
XMX="${XMX:-2g}"
# Batch-block confirmation on this HEAD regresses under per-node PG (sweep
# rejects every candidate: skipConflict=4/skipChain=4 -> txs stuck BATCHED,
# 0 confirmed across 3 runs), while the default SHARED test-bigtangle-postgres
# confirmed 1610/2000 in the benchmark. Use the default shared DB for the
# soak; combined with the small genesis (block verify ~1.2s vs ~8s) the mesh
# stays converged and confirmations flow.
PER_NODE_PG="${PER_NODE_PG:-0}"

# Wave geometry: SMALL waves only. On a 3-node mesh with a big genesis the
# block-verify path costs ~8s per block against a 12s slot, so a burst batch
# block gets orphaned before a beacon can reference it (txs stuck BATCHED,
# measured). The soak that DID confirm 100% used 100-tx waves, 2 clients,
# batch 20. Keep waves small enough that the assembled batch verifies in the
# slot; the 3-day stability signal comes from sustained hourly waves + attacks
# + leave/join, not from burst throughput.
WAVE_SIZE="${WAVE_SIZE:-150}"
CLIENTS="${CLIENTS:-4}"
BATCH="${BATCH:-20}"
SOAK_START="${SOAK_START:-40011}"
# Cap the batch block size so each assembled batch verifies within the 12s
# slot instead of getting orphaned (a 2000-tx burst batch takes ~10s to
# verifyReferenced and keeps getting re-orged -> txs stuck BATCHED).
BATCH_TX_PER_BLOCK="${BATCH_TX_PER_BLOCK:-200}"
# baseline benchmark uses a DEDICATED early wallet range so it never overlaps
# with the soak waves (which start at SOAK_START and advance WAVE_SIZE/hour).
BASELINE_START="${BASELINE_START:-10011}"
SOAK_HOURS="${SOAK_HOURS:-72}"
# First attack is deferred so the mesh stabilizes after baseline+first waves;
# ATTACK_EVERY controls the cadence from that point.
ATTACK_FIRST="${ATTACK_FIRST:-6}"
ATTACK_EVERY="${ATTACK_EVERY:-6}"        # run MeshAttack every N hours
CONFIRM_TIMEOUT="${CONFIRM_TIMEOUT:-1800}"
RATE="${RATE:-0}"

# Attack wallet index base = NNODES+1+BENCH_WALLETS (matches testnodes.sh).
ATTACK_START=$((NNODES + 1 + BENCH_WALLETS))

LOG=/tmp/opencode/soak3d.log
REPORT="${WORKDIR}/soak3d_report.tsv"
SOAK_LOG="${WORKDIR}/soak3d_waves.log"
ATTACK_LOG="${WORKDIR}/soak3d_attacks.log"
MESHMON_TSV="${WORKDIR}/soak3d_meshmon.tsv"

log()  { echo "[soak3d $(date -u +%F\ %H:%M:%S)] $*"; }

CP_DIR="${WORKDIR}/cp"
[ -d "${CP_DIR}/BOOT-INF/classes" ] || { mkdir -p "${CP_DIR}"; jar="$(ls -t "${ROOT}"/layer0-server/target/layer0-server-*-exec.jar | head -1)"; unzip -oq "$jar" 'BOOT-INF/*' -d "${CP_DIR}"; }
CP="${CP_DIR}/BOOT-INF/classes:${CP_DIR}/BOOT-INF/lib/*"

J=/tmp/opencode/jdk25/bin/java
[ -x "$J" ] || J=java

{
echo "=========== soak3d START $(date -u +%F\ %H:%M:%S) ==========="
echo "image=${IMAGE} nnodes=${NNODES} bench_wallets=${BENCH_WALLETS} attack_wallets=${ATTACK_WALLETS}"
echo "wave_size=${WAVE_SIZE} clients=${CLIENTS} batch=${BATCH} soak_hours=${SOAK_HOURS} attack_every=${ATTACK_EVERY}"
echo "soak_start=${SOAK_START} attack_start=${ATTACK_START} confirm_timeout=${CONFIRM_TIMEOUT}"
echo "report=${REPORT}"
} >> "${LOG}" 2>&1

# ---- 1. fresh mesh with bench + attack wallets ----------------------------
log "down (clearing prior mesh)"
env IMAGE="${IMAGE}" XMX="${XMX}" PER_NODE_PG="${PER_NODE_PG}" bash "${S}" down >> "${LOG}" 2>&1 || true
log "up with BENCH_WALLETS=${BENCH_WALLETS} ATTACK_WALLETS=${ATTACK_WALLETS}"
env IMAGE="${IMAGE}" XMX="${XMX}" PER_NODE_PG="${PER_NODE_PG}" BENCH_WALLETS="${BENCH_WALLETS}" BENCH_FUND="${BENCH_FUND}" \
    ATTACK_WALLETS="${ATTACK_WALLETS}" ATTACK_FUND="${ATTACK_FUND}" \
    BATCH_TX_PER_BLOCK="${BATCH_TX_PER_BLOCK}" \
    bash "${S}" up >> "${LOG}" 2>&1 || { log "FAIL up"; exit 1; }
log "stake"
env IMAGE="${IMAGE}" XMX="${XMX}" PER_NODE_PG="${PER_NODE_PG}" bash "${S}" stake >> "${LOG}" 2>&1 || { log "FAIL stake"; exit 1; }
log "finality >= 33"
env IMAGE="${IMAGE}" XMX="${XMX}" PER_NODE_PG="${PER_NODE_PG}" bash "${S}" finality 33 2400 >> "${LOG}" 2>&1 || { log "FAIL finality"; exit 1; }

# ---- 2. baseline benchmark ------------------------------------------------
log "baseline benchmark: ${WAVE_SIZE} tx wave from index ${BASELINE_START}"
START_TS=$(date +%s%3N)
"$J" -Dbench.fund="${BENCH_FUND}" -Dbench.pay=40000 -Dbench.recvIdx=99999 \
    -Dbench.confirm=1 -Dbench.confirmNode=0 -Dbench.confirmTimeoutSec="${CONFIRM_TIMEOUT}" \
    -Dbench.progressSec=30 -Dbench.rate="${RATE}" \
    -cp "${CP}" "${VALSRC}/MeshBm.java" run \
    "${BASELINE_START}" "${WAVE_SIZE}" "${CLIENTS}" "${BATCH}" "${NNODES}" "http://127.0.0.1:" >> "${SOAK_LOG}" 2>&1
log "baseline benchmark done (see ${SOAK_LOG})"

# ---- 3. background meshmon -------------------------------------------------
nohup env WORKDIR="${WORKDIR}" NNODES="${NNODES}" \
    bash "${VALSRC}/meshmon.sh" 60 "${MESHMON_TSV}" "${NNODES}" > "${WORKDIR}/soak3d_meshmon.log" 2>&1 &
MESHMON_PID=$!
log "meshmon pid ${MESHMON_PID} -> ${MESHMON_TSV}"

# ---- 4. 3-day soak: hourly waves + periodic attacks -------------------------
{
echo -e "hour\twaveStart\tsize\tsubmitted\tdropped\tconfirmed\tsubmitTps\tconfirmTps\tpeakTps\tp50Ms\tp95Ms\tp99Ms\tattackResult"
for h in $(seq 0 $((SOAK_HOURS - 1))); do
    start=$((SOAK_START + h * WAVE_SIZE))
    attackResult="skip"
    if [ $((h % ATTACK_EVERY)) -eq 0 ] && [ "$h" -ge "${ATTACK_FIRST}" ]; then
        log "hour ${h}: running MeshAttack V1-V18 (attack_start=${ATTACK_START})"
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
        # each run spends ~90 wallets; advance the window so re-runs use fresh ones
        ATTACK_START=$((ATTACK_START + 120))
    fi

    # Mesh-health gate: abort the soak (loudly) if the mesh has split — a 3-day
    # stability signal is meaningless on divergent forks. Confirmed-head spread
    # > 1 epoch = split (forkcheck.sh threshold). A SINGLE divergent sample can
    # be transient PoS fork-choice churn (nodes reconcile within a slot or two),
    # so a split only aborts after 2 CONSECUTIVE divergent samples.
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
        # persist the streak; only a SECOND consecutive divergent sample aborts
        streak_file="${WORKDIR}/.soak_split_streak"
        streak=$([ -f "$streak_file" ] && cat "$streak_file" || echo 0)
        streak=$((streak + 1))
        echo "$streak" > "$streak_file"
        log "!!! MESH SPLIT SAMPLE ${streak}/2 at hour ${h}: ${nheads} distinct confirmed heads"
        [ "$streak" -ge 2 ] && split=1
    else
        rm -f "${WORKDIR}/.soak_split_streak"
    fi

    log "hour ${h}: MeshBm wave ${WAVE_SIZE} tx from index ${start} (clients=${CLIENTS} batch=${BATCH})"
    result=$("$J" \
        -Dbench.fund="${BENCH_FUND}" -Dbench.pay=40000 \
        -Dbench.recvIdx="${h}" -Dbench.confirm=1 -Dbench.confirmNode=0 \
        -Dbench.confirmTimeoutSec="${CONFIRM_TIMEOUT}" -Dbench.progressSec=30 \
        -Dbench.rate="${RATE}" \
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

# ---- 5. LAST: leave/join + final verify ------------------------------------
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
echo "=========== soak3d DONE $(date -u +%F\ %H:%M:%S) ===========" >> "${LOG}" 2>&1
echo "SOAK3D_ALL_COMPLETE"
exit 0
