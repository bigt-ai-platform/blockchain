#!/usr/bin/env bash
# boot_mesh.sh — manual, memory-aware 10-node boot for the hermetic mesh.
# Bypasses testnodes.sh's wave logic (which proved fragile under concurrent
# runs) while keeping the memory-settle gate: boots NODES_PER_WAVE nodes,
# waits for their APIs, waits for RSS to settle, then the next wave.
#
# Env: WORKDIR (default /tmp/bt4test-mine), NNODES, NODES_PER_WAVE,
#      SETTLE_MB (per-node RSS gate), API_TIMEOUT (seconds per wave).
set -uo pipefail
WORKDIR="${WORKDIR:-/tmp/bt4test-mine}"
NNODES="${NNODES:-10}"
NODES_PER_WAVE="${NODES_PER_WAVE:-2}"
SETTLE_MB="${SETTLE_MB:-1600}"
API_TIMEOUT="${API_TIMEOUT:-400}"
CONTAINER_PREFIX="${CONTAINER_PREFIX:-bt5-node-}"

log() { echo "[boot $(date -u +%H:%M:%S)] $*"; }

mem_mib() { # $1=container -> MiB (float) or 0
    local out m val unit
    out=$(docker stats --no-stream --format '{{.MemUsage}}' "$1" 2>/dev/null)
    m=$(printf '%s' "$out" | grep -oE '^[0-9.]+ ?[MG]iB' | head -1)
    [ -z "$m" ] && { echo 0; return; }
    val=$(printf '%s' "$m" | grep -oE '^[0-9.]+')
    unit=$(printf '%s' "$m" | grep -oE '[MG]iB')
    if [ "$unit" = "GiB" ]; then
        python3 -c "print('%.0f' % (float('$val')*1024))"
    else
        python3 -c "print('%.0f' % float('$val'))"
    fi
}

for wstart in $(seq 0 "$NODES_PER_WAVE" $((NNODES - 1))); do
    wend=$((wstart + NODES_PER_WAVE - 1)); [ "$wend" -ge "$NNODES" ] && wend=$((NNODES - 1))
    log "wave $((wstart / NODES_PER_WAVE + 1)): nodes ${wstart}..${wend}"
    for i in $(seq "$wstart" "$wend"); do
        ( cd "${WORKDIR}/node-${i}" && bash -c "
            set -euo pipefail
            source ../common.env; source ./validator.env
            source ../validator_common.sh
            start_server" ) > "${WORKDIR}/node-${i}/start.log" 2>&1 &
    done
    wait
    # Poll this wave's APIs.
    for i in $(seq "$wstart" "$wend"); do
        local_ok=0
        for _ in $(seq 1 $((API_TIMEOUT / 3))); do
            if [ "$(curl -s -m 2 -o /dev/null -w '%{http_code}' "http://127.0.0.1:$((8281 + i))/" 2>/dev/null)" = "200" ]; then
                local_ok=1; break
            fi
            sleep 3
        done
        [ "$local_ok" = 1 ] || log "WARN node-${i} API never came up"
    done
    # Settle: wait until every container's RSS is under SETTLE_MB.
    settled=0
    for _ in $(seq 1 60); do
        cur=0
        for i in $(seq "$wstart" "$wend"); do
            m=$(mem_mib "${CONTAINER_PREFIX}node-${i}-server")
            cur=$(python3 -c "print(max($cur, $m))")
        done
        if [ "${cur%.*}" -le "$SETTLE_MB" ] 2>/dev/null; then settled=1; break; fi
        sleep 10
    done
    log "wave $((wstart / NODES_PER_WAVE + 1)) settled (peak ${cur}MiB, gate ${SETTLE_MB}MiB)"
done
log "ALL ${NNODES} NODES BOOTED"
