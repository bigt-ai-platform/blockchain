#!/usr/bin/env bash
# soak10.sh — fast-cadence load + attack soak on the RUNNING 3-node mesh.
# Every 10 minutes: one MeshBm wave (150 tx) + one full MeshAttack suite
# (V1-V36 at reduced scale so 2000 attack wallets cover the whole window).
#
# 6h validation = 36 cycles. At the end: leave 2 -> verify -> join 2 ->
# finality -> verify (the "last" step).
#
# Env: WORKDIR, NNODES, WAVE_SIZE, CLIENTS, BATCH, CYCLES, CYCLE_SEC,
# SOAK_START, ATTACK_START, ATTACK_SCALE, IMAGE, PER_NODE_PG.
set -u

ROOT="$(cd "$(dirname "$0")/../../.." && pwd)"
S="${ROOT}/helper/prod/testnodes.sh"
VALSRC="${ROOT}/helper/prod/validators"
WORKDIR="${WORKDIR:-/tmp/bt4test}"
NNODES="${NNODES:-3}"
IMAGE="${IMAGE:-layer0-server:soak6}"
PER_NODE_PG="${PER_NODE_PG:-1}"
XMX="${XMX:-2g}"

BENCH_FUND="${BENCH_FUND:-50000}"
ATTACK_FUND="${ATTACK_FUND:-30000}"

WAVE_SIZE="${WAVE_SIZE:-150}"
CLIENTS="${CLIENTS:-4}"
BATCH="${BATCH:-20}"
SOAK_START="${SOAK_START:-40011}"
# attack wallets are funded at [4+BENCH_WALLETS, 4+BENCH_WALLETS+ATTACK_WALLETS)
ATTACK_START=$((4 + ${BENCH_WALLETS:-51000}))
ATTACK_SCALE="${ATTACK_SCALE:-0.25}"   # ~22 wallets/run -> fits 2000 over 36 runs
CYCLES="${CYCLES:-36}"                 # 6h at 10-min cadence
CYCLE_SEC="${CYCLE_SEC:-600}"          # 10 minutes
CONFIRM_TIMEOUT="${CONFIRM_TIMEOUT:-600}"
# First attack is deferred a couple cycles so the mesh is warm.
ATTACK_FIRST="${ATTACK_FIRST:-2}"

LOG=/tmp/opencode/soak10.log
REPORT="${WORKDIR}/soak10_report.tsv"
WAVES_LOG="${WORKDIR}/soak10_waves.log"
ATTACK_LOG="${WORKDIR}/soak10_attacks.log"
MESHMON_TSV="${WORKDIR}/soak10_meshmon.tsv"

log() { echo "[soak10 $(date -u +%F\ %H:%M:%S)] $*"; }

CP_DIR="${WORKDIR}/cp"
[ -d "${CP_DIR}/BOOT-INF/classes" ] || { mkdir -p "${CP_DIR}"; jar="$(ls -t "${ROOT}"/layer0-server/target/layer0-server-*-exec.jar | head -1)"; unzip -oq "$jar" 'BOOT-INF/*' -d "${CP_DIR}"; }
CP="${CP_DIR}/BOOT-INF/classes:${CP_DIR}/BOOT-INF/lib/*"
J=/tmp/opencode/jdk25/bin/java
[ -x "$J" ] || J=java

{
echo "=========== soak10 START $(date -u +%F\ %H:%M:%S) ==========="
echo "nnodes=${NNODES} image=${IMAGE} cycles=${CYCLES} cycle=${CYCLE_SEC}s"
echo "wave_size=${WAVE_SIZE} attack_scale=${ATTACK_SCALE} attack_start=${ATTACK_START}"
echo "report=${REPORT}"
} >> "${LOG}" 2>&1

# mesh health sampler
nohup env WORKDIR="${WORKDIR}" NNODES="${NNODES}" \
    bash "${VALSRC}/meshmon.sh" 30 "${MESHMON_TSV}" "${NNODES}" > "${WORKDIR}/soak10_meshmon.log" 2>&1 &
MESHMON_PID=$!
log "meshmon pid ${MESHMON_PID}"

{
echo -e "cycle\twaveStart\tsize\tsubmitted\tdropped\tconfirmed\tsubmitTps\tconfirmTps\tpeakTps\tp50Ms\tp95Ms\tp99Ms\tattackResult"
for c in $(seq 0 $((CYCLES - 1))); do
    start=$((SOAK_START + c * WAVE_SIZE))
    attackResult="skip"
    if [ "$c" -ge "${ATTACK_FIRST}" ]; then
        log "cycle ${c}: running MeshAttack V1-V36 scale=${ATTACK_SCALE} (start=${ATTACK_START})"
        attackResult="FAIL"
        if "$J" -Dattack.fund="${ATTACK_FUND}" -Dattack.pay=20000 \
            -Dattack.confirmTimeoutSec=240 \
            -cp "${CP}" "${VALSRC}/MeshAttack.java" run \
            "${ATTACK_START}" 200 "${NNODES}" "http://127.0.0.1:" "${ATTACK_SCALE}" \
            > "${ATTACK_LOG}.${c}" 2>&1; then
            attackResult="PASS"
        fi
        grep -E "\[DEFLECTED\]|\[BREACH\]|ALL_ATTACKS_DEFLECTED|ATTACK_BREACH" "${ATTACK_LOG}.${c}" >> "${LOG}" 2>&1
        tail -10 "${ATTACK_LOG}.${c}" >> "${LOG}" 2>&1
        log "cycle ${c}: MeshAttack result=${attackResult}"
        ATTACK_START=$((ATTACK_START + 40))
    fi

    log "cycle ${c}: MeshBm wave ${WAVE_SIZE} tx from index ${start}"
    result=$("$J" \
        -Dbench.fund="${BENCH_FUND}" -Dbench.pay=40000 \
        -Dbench.recvIdx="${c}" -Dbench.confirm=1 -Dbench.confirmNode=0 \
        -Dbench.confirmTimeoutSec="${CONFIRM_TIMEOUT}" -Dbench.progressSec=20 \
        -Dbench.rate=0 \
        -cp "${CP}" "${VALSRC}/MeshBm.java" run \
        "${start}" "${WAVE_SIZE}" "${CLIENTS}" "${BATCH}" "${NNODES}" "http://127.0.0.1:" 2>&1)
    echo "$result" >> "${WAVES_LOG}"
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
        "$c" "$start" "${WAVE_SIZE}" "${sub:-0}" "${drop:-0}" "${conf:-0}" \
        "${stps:-0}" "${ctps:-0}" "${ptps:-0}" "${p50:-0}" "${p95:-0}" "${p99:-0}" "${attackResult}"
    log "cycle ${c} done: submitted=${sub:-0} confirmed=${conf:-0} attack=${attackResult}"
    if [ "$c" -lt $((CYCLES - 1)) ]; then
        sleep "${CYCLE_SEC}"
    fi
done
} > "${REPORT}" 2>&1
log "soak10 cycles finished — report ${REPORT}"

# ---- LAST: leave/join + final verify -----------------------------------------
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
echo "=========== soak10 DONE $(date -u +%F\ %H:%M:%S) ===========" >> "${LOG}" 2>&1
echo "SOAK10_COMPLETE"
exit 0
