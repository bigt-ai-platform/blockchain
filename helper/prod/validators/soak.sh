#!/usr/bin/env bash
# soak.sh — sustained long-window load against the hermetic testnodes mesh.
# Fires one MeshBm `run` wave every WAVE_INTERVAL_SEC, each wave spending a
# fresh slice of the genesis-funded benchmark wallets, and appends the
# per-wave MESHBM result block to a report. Designed to run for hours in the
# background (nohup/setsid).
#
# Env: WORKDIR NNODES WAVE_SIZE WAVE_COUNT WAVE_INTERVAL_SEC SOAK_START
#      CLIENTS BATCH CONFIRM_TIMEOUT REPORT
# Bash env is NOT inherited by MeshBm (java source mode), so the interesting
# knobs are passed as -Dbench.* properties on the java command line.
set -u

WORKDIR="${WORKDIR:-/tmp/bt4test}"
NNODES="${NNODES:-10}"
ROOT="$(cd "$(dirname "$0")/../../.." && pwd)"
VALSRC="${ROOT}/helper/prod/validators"

WAVE_SIZE="${WAVE_SIZE:-3000}"
WAVE_COUNT="${WAVE_COUNT:-20}"
WAVE_INTERVAL_SEC="${WAVE_INTERVAL_SEC:-1200}"
SOAK_START="${SOAK_START:-40011}"
CLIENTS="${CLIENTS:-16}"
BATCH="${BATCH:-200}"
CONFIRM_TIMEOUT="${CONFIRM_TIMEOUT:-900}"
REPORT="${REPORT:-${WORKDIR}/soak_report.tsv}"
SOAK_LOG="${SOAK_LOG:-${WORKDIR}/soak.log}"
PAY="${PAY:-40000}"
FUND="${FUND:-50000}"
RATE="${RATE:-0}"
CONFIRM_NODE="${CONFIRM_NODE:-0}"

# java source-file mode needs the unpacked exec-jar classpath.
if [ ! -d "${WORKDIR}/cp/BOOT-INF/classes" ]; then
    mkdir -p "${WORKDIR}/cp"
    jar="$(ls -t "${ROOT}"/layer0-server/target/layer0-server-*-exec.jar | head -1)"
    unzip -oq "$jar" 'BOOT-INF/*' -d "${WORKDIR}/cp"
fi
CP="${WORKDIR}/cp/BOOT-INF/classes:${WORKDIR}/cp/BOOT-INF/lib/*"

log() { echo "[soak $(date -u +%H:%M:%S)] $*"; }
{
    echo -e "wave\tstartIndex\tsize\tsubmitted\tdropped\tconfirmed\tsubmitWallMs\tconfirmWallMs\tsubmitTps\tconfirmTps\tpeakTps\tp50Ms\tp95Ms\tp99Ms"
    for w in $(seq 0 $((WAVE_COUNT - 1))); do
        start=$((SOAK_START + w * WAVE_SIZE))
        if [ "$w" -gt 0 ]; then
            log "wave ${w} sleeping ${WAVE_INTERVAL_SEC}s (next start=${start})"
            sleep "${WAVE_INTERVAL_SEC}"
        fi
        log "wave ${w}: ${WAVE_SIZE} tx from index ${start} (clients=${CLIENTS} batch=${BATCH})"
        # java -D props MUST precede the source file.
        result=$(/tmp/opencode/jdk25/bin/java \
            -Dbench.fund="${FUND}" -Dbench.pay="${PAY}" \
            -Dbench.recvIdx="${w}" -Dbench.confirm=1 -Dbench.confirmNode="${CONFIRM_NODE}" \
            -Dbench.confirmTimeoutSec="${CONFIRM_TIMEOUT}" -Dbench.progressSec=30 \
            -Dbench.rate="${RATE}" \
            -cp "${CP}" "${VALSRC}/MeshBm.java" run \
            "${start}" "${WAVE_SIZE}" "${CLIENTS}" "${BATCH}" "${NNODES}" "http://127.0.0.1:" 2>&1)
        rc=$?
        echo "$result" >> "${SOAK_LOG}"
        sub=$(echo "$result" | grep -oP 'SUBMITTED=\K[0-9]+' | head -1)
        drop=$(echo "$result" | grep -oP 'DROPPED=\K[0-9]+' | head -1)
        conf=$(echo "$result" | grep -oP 'CONFIRMED=\K[0-9]+' | head -1)
        sw=$(echo "$result" | grep -oP 'SUBMIT_WALL_MS=\K[0-9]+' | head -1)
        cw=$(echo "$result" | grep -oP 'CONFIRM_WALL_MS=\K[0-9]+' | head -1)
        stps=$(echo "$result" | grep -oP 'SUBMIT_TPS=\K[0-9.]+' | head -1)
        ctps=$(echo "$result" | grep -oP 'CONFIRM_TPS=\K[0-9.]+' | head -1)
        ptps=$(echo "$result" | grep -oP 'PEAK_TPS=\K[0-9.]+' | head -1)
        p50=$(echo "$result" | grep -oP 'P50_MS=\K[0-9]+' | head -1)
        p95=$(echo "$result" | grep -oP 'P95_MS=\K[0-9]+' | head -1)
        p99=$(echo "$result" | grep -oP 'P99_MS=\K[0-9]+' | head -1)
        printf '%d\t%d\t%d\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' \
            "$w" "$start" "${WAVE_SIZE}" "${sub:-0}" "${drop:-0}" "${conf:-0}" \
            "${sw:-0}" "${cw:-0}" "${stps:-0}" "${ctps:-0}" "${ptps:-0}" \
            "${p50:-0}" "${p95:-0}" "${p99:-0}"
        log "wave ${w} done: submitted=${sub:-0} confirmed=${conf:-0} submitTPS=${stps:-0} confirmTPS=${ctps:-0} (rc=${rc})"
    done
} > "${REPORT}" 2>&1
log "soak finished — report at ${REPORT}"
