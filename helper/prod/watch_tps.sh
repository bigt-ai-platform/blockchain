#!/usr/bin/env bash
# Live confirmed-TPS ticker: counts REAL transfer outputs (coinbase=false)
# absorbed into the CONFIRMED beacon chain (blocks.confirmed=true).
# Prints rolling 5-sample TPS; cl/finalized alongside.
DBPSQL="docker exec test-bigtangle-postgres psql -U root -d bt4_0 -tAc"
prev_v=0; prev_t=$(date +%s); win_v=0; win_t=$prev_t
echo "time     confirmed_total   tps(3s)   tps(15s)   cl/fin"
while true; do
  v=$(timeout 20 $DBPSQL "select count(*) from outputs o join blocks b on b.hash=o.blockhash where o.coinbase=false and b.confirmed=true" 2>/dev/null)
  case "$v" in ''|*[!0-9]*) sleep 3; continue;; esac
  now=$(date +%s); dt=$((now-prev_t)); [ $dt -eq 0 ] && dt=1
  dv=$((v-prev_v))
  tps3=$((dv/dt))
  dw=$((now-win_t)); if [ $dw -ge 15 ]; then tps15=$(( (v-win_v)/dw )); win_v=$v; win_t=$now; fi
  s=$(curl -s -m 4 -X POST http://127.0.0.1:8281/getChainNumber -H 'Content-Type: application/json' -d '{}' | python3 -c 'import sys,json
try:
 d=json.load(sys.stdin); print((d.get("txReward") or {}).get("chainLength"), d.get("finalizedChainLength"))
except Exception: print("-")' 2>/dev/null)
  echo "$(date +%T) $v ${tps3} ${tps15:-0} $s"
  prev_v=$v; prev_t=$now
  sleep 3
done
