#!/usr/bin/env bash
# meshmon.sh — live health sampler for the hermetic testnodes mesh.
# Samples every reachable node every INTERVAL seconds: chainLength,
# finalized length, validator count, confirmed head (short hex). Detects
# divergent heads (mesh split) and crashes. Writes one TSV row per sample
# to OUT and prints a compact status line to stdout.
#
# Usage: meshmon.sh [interval] [out] [nnodes]
#   INTERVAL  default 30 (seconds)
#   OUT       default $WORKDIR/meshmon.tsv
#   NNODES    default 10
set -u

INTERVAL="${1:-30}"
WORKDIR="${WORKDIR:-/tmp/bt4test}"
OUT="${2:-${WORKDIR}/meshmon.tsv}"
NNODES="${3:-${NNODES:-10}}"

api() {
    curl -s -m 5 -X POST "http://127.0.0.1:$((8281 + $1))/getChainNumber" \
        -H 'Content-Type: application/json' -d '{}' 2>/dev/null || true
}

fmt() { # fields: ts cl fin val head
    local ts="$1" cl="$2" fin="$3" val="$4" head="$5"
    printf '%s\t%s\t%s\t%s\t%s\n' "$ts" "$cl" "$fin" "$val" "$head"
}

: > "$OUT"
echo "[meshmon] sampling $NNODES nodes every ${INTERVAL}s -> $OUT (pid $$)"
while true; do
    ts=$(date +%s)
    heads=""
    line=""
    for i in $(seq 0 $((NNODES - 1))); do
        r=$(api "$i")
        cl=$(printf '%s' "$r" | python3 -c "
import sys, json
try:
    d=json.load(sys.stdin); r=d.get('txReward'); r=json.loads(r) if isinstance(r,str) else (r or {})
    print(r.get('chainLength',0))
except Exception: print('-')")
        fin=$(printf '%s' "$r" | python3 -c "
import sys, json
try:
    d=json.load(sys.stdin)
    print(d.get('finalizedChainLength') if d.get('finalizedChainLength') is not None else '-')
except Exception: print('-')")
        val=$(curl -s -m 5 -X POST "http://127.0.0.1:$((8281 + i))/getValidators" -H 'Content-Type: application/json' -d '{}' 2>/dev/null | python3 -c "
import sys, json
try:
    d=json.load(sys.stdin); v=d.get('text') or d.get('validators')
    v=json.loads(v) if isinstance(v,str) else v
    v=(v or {}).get('validators') if isinstance(v,dict) else v
    print(len(v or []))
except Exception: print('-')")
        head=$(printf '%s' "$r" | python3 -c "
import sys, json, base64
try:
    d=json.load(sys.stdin); r=d.get('txReward') or {}
    b=(r.get('blockHash') or {}).get('bytes')
    print(base64.b64decode(b).hex()[:12] if b else '-')
except Exception: print('-')")
        line="${line}cl=$cl/fin=$fin/v=$val "
        printf '%s\t%d\t%s\t%s\t%s\t%s\n' "$ts" "$i" "$cl" "$fin" "$val" "$head" >> "$OUT"
        [ -n "$head" ] && [ "$head" != "-" ] && heads="${heads}${head}\n"
    done
    distinct=$(printf '%b' "$heads" | sort -u | grep -c . || true)
    div=""
    if [ "${distinct:-0}" -gt 1 ]; then div="  *** DIVERGENCE (${distinct} heads) ***"; fi
    echo "[$(date -u +%H:%M:%S)] $line| $div"
    sleep "$INTERVAL"
done
