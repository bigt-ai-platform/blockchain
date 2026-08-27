#!/usr/bin/env bash
#
# NOTE: session driver — W=/tmp/opencode and JDK paths must be adapted
# to the host (see header vars). Kept for reproducibility of the
# 2026-08-27 TPS campaign (see docs/performance.md).
set -u
N=$1; TAG=$2
W=/tmp/opencode
echo "== SCALE N=$N start $(date +%T)"
for i in $(seq 0 4); do timeout 15 docker rm -f bt4-node-node-$i-server >/dev/null 2>&1 || true; done
sleep 2
for i in $(seq 0 4); do timeout 25 docker exec test-bigtangle-postgres psql -U root -d postgres -c "DROP DATABASE IF EXISTS bt4_$i;" >/dev/null 2>&1 || true; done
IMAGE=ghcr.io/bigt-ai-platform/layer0-server:bench NNODES=$N timeout 380 bash /config/git/blockchain/helper/prod/testnodes.sh up || exit 1
IMAGE=ghcr.io/bigt-ai-platform/layer0-server:bench NNODES=$N timeout 650 bash /config/git/blockchain/helper/prod/testnodes.sh stake >/dev/null 2>&1 || exit 1
cl=0
for k in $(seq 1 14); do sleep 30; cl=$(curl -s -m 4 -X POST http://127.0.0.1:8281/getChainNumber -H 'Content-Type: application/json' -d '{}' | python3 -c 'import sys,json; print((json.load(sys.stdin).get("txReward") or {}).get("chainLength") or 0)' 2>/dev/null); echo "[ready] cl=$cl"; [ "${cl:-0}" -ge 18 ] && break; done
$JDK -Dload.nnodes=$N -cp "$W/tlclasses:/tmp/bt4test/cp/BOOT-INF/classes:/tmp/bt4test/cp/BOOT-INF/lib/*" MeshBench fund 10000 25 250 http://127.0.0.1: 3500000000 2>&1 | grep FUND
cp $W/meshwallets.txt $W/meshwallets_$TAG.txt
setsid nohup python3 $W/scale_confirm.py $W/meshwallets_$TAG.txt $N > $W/confirm_$TAG.log 2>&1 &
echo WATCHER_UP
$JDK -Dload.nnodes=$N -cp "$W/tlclasses:/tmp/bt4test/cp/BOOT-INF/classes:/tmp/bt4test/cp/BOOT-INF/lib/*" MeshBench run 10000 25 250 http://127.0.0.1: 3500000000 2>&1 | grep -aE "SUBMIT done|client.*failed"
echo "== RUN DONE $(date +%T) — watcher continues; poll confirm_$TAG.log =="
sleep 240
tail -4 $W/confirm_$TAG.log
NN=$(($N)); BAN=""
docker ps --format '{{.Names}} {{.Status}}' | grep node | wc -l
