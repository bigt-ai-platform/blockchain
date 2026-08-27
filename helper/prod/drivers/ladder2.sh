#!/usr/bin/env bash
#
# NOTE: session driver — W=/tmp/opencode and JDK paths must be adapted
# to the host (see header vars). Kept for reproducibility of the
# 2026-08-27 TPS campaign (see docs/performance.md).
set -u
W=/tmp/opencode; J=$W/jdk25/bin/java
CP="$W/tlclasses:/tmp/bt4test/cp/BOOT-INF/classes:/tmp/bt4test/cp/BOOT-INF/lib/*"
T=/config/git/blockchain/helper/prod/testnodes.sh
SUM=$W/ladder2_summary.log
echo "== LADDER2 START $(date +%T)" | tee -a $SUM

cycle () { # $1=N  $2=tag
  local N=$1 TAG=$2
  echo "== CYCLE N=$N $(date +%T)" | tee -a $SUM
  for i in $(seq 0 4); do timeout 15 docker rm -f bt4-node-node-$i-server >/dev/null 2>&1 || true; done; sleep 2
  for i in $(seq 0 4); do timeout 25 docker exec test-bigtangle-postgres psql -U root -d postgres -c "DROP DATABASE IF EXISTS bt4_$i;" >/dev/null 2>&1 || true; done
  IMAGE=ghcr.io/bigt-ai-platform/layer0-server:bench NNODES=$N timeout 380 bash $T up || { echo "UP_FAIL N=$N" | tee -a $SUM; return; }
  IMAGE=ghcr.io/bigt-ai-platform/layer0-server:bench NNODES=$N timeout 700 bash $T stake >/dev/null 2>&1
  cl=0; for k in $(seq 1 14); do sleep 25; cl=$(curl -s -m 4 -X POST http://127.0.0.1:8281/getChainNumber -H 'Content-Type: application/json' -d '{}' | python3 -c 'import sys,json; print((json.load(sys.stdin).get("txReward") or {}).get("chainLength") or 0)' 2>/dev/null); [ "${cl:-0}" -ge 18 ] && break; done
  echo "[ready] cl=$cl" | tee -a $SUM
  IDX=$((6_500_000_000 + N * 100000000))
  $J -Dload.nnodes=$N -cp "$CP" MeshBench fund 12000 25 250 http://127.0.0.1: $IDX 2>&1 | grep -aE "FUND done|FUNDFAIL" | tail -2 | tee -a $SUM
  cp $W/meshwallets.txt $W/meshwallets_$TAG.txt
  setsid nohup python3 $W/scale_confirm.py $W/meshwallets_$TAG.txt $N > $W/confirm_$TAG.log 2>&1 &
  WP=$!
  # burst offer
  $J -Dload.nnodes=$N -cp "$CP" MeshBench run 12000 25 250 http://127.0.0.1: $IDX > $W/burst_$TAG.log 2>&1
  grep -a "SUBMIT done" $W/burst_$TAG.log | tee -a $SUM
  # 240s drain/observe window (watcher samples every 10s)
  sleep 240
  kill $WP 2>/dev/null
  # stability snapshot
  BAN=$(NNODES=$N bash $T status 2>/dev/null | grep -c "DIVERGENCE")
  FIN=$(curl -s -m 4 -X POST http://127.0.0.1:8281/getChainNumber -H 'Content-Type: application/json' -d '{}' | python3 -c 'import sys,json
try:
 d=json.load(sys.stdin); print((d.get("txReward") or {}).get("chainLength"), d.get("finalizedChainLength"))
except Exception: print("-")' 2>/dev/null)
  echo "RESULT N=$N diverge_banner=$BAN tip/fin=$FIN" | tee -a $SUM
}

cycle 1 n1
cycle 3 n3

# ---- N=5 + JOIN-STORM AUDIT ----
cycle 5 n5
echo "== JOIN-STORM AUDIT (node-4, 2 cycles fresh-key rejoin)" | tee -a $SUM
for round in 1 2; do
  cl_before=$(curl -s -m 4 -X POST http://127.0.0.1:8281/getChainNumber -H 'Content-Type: application/json' -d '{}' | python3 -c 'import sys,json; print((json.load(sys.stdin).get("txReward") or {}).get("chainLength") or 0)' 2>/dev/null)
  timeout 15 docker rm -f bt4-node-node-4-server >/dev/null 2>&1
  sleep 5
  ( IMAGE=ghcr.io/bigt-ai-platform/layer0-server:bench NNODES=5 timeout 900 bash $T join 4 ) >> $W/joinstorm.log 2>&1
  sleep 60
  cl_after=$(curl -s -m 4 -X POST http://127.0.0.1:8281/getChainNumber -H 'Content-Type: application/json' -d '{}' | python3 -c 'import sys,json; print((json.load(sys.stdin).get("txReward") or {}).get("chainLength") or 0)' 2>/dev/null)
  snap=$(docker exec test-bigtangle-postgres psql -U root -d bt4_0 -tAc "select count(*) from pos_state where service='posvalidators' and value::text <> ''" 2>/dev/null)
  vals=$(NNODES=5 bash $T status 2>/dev/null | grep -oE "validators=[0-9]+" | head -1)
  echo "STORM round=$round cl $cl_before -> $cl_after | snapshots=$snap | $vals" | tee -a $SUM
done
echo "ALL DONE $(date +%T)" | tee -a $SUM
