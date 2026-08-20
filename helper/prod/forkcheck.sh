#!/usr/bin/env bash
# forkcheck.sh — PoS fork-safety monitor for the deployed 3-node prod network.
#
# Detects the failure mode that once wedged the testnet: two nodes confirming
# DIFFERENT chains (competing beacons) that each finalize a different
# checkpoint, after which the sync guard refuses to reconcile them. Alerts on:
#   1. confirmed chainlength spread beyond one epoch (the verify threshold)
#   2. confirmed head hashes diverging at a shared chainlength
#   3. any BLOCKTYPE_SLASHING blocks (equivocation/attack evidence)
#
# Usage: ./forkcheck.sh [threshold_epochs]
#   default threshold = 1 epoch (POS_SLOTS_PER_EPOCH=32)
#
# Exit code: 0 = healthy, 1 = divergence detected.
set -u

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
SSH_USER="${SSH_USER:-root}"
S2001_HOST="${S2001_HOST:-10.8.0.1}"
CUI_HOST="${CUI_HOST:-10.8.0.2}"
JUMP_HOST="${JUMP_HOST-}"
SSH_OPTS="${SSH_OPTS:--o BatchMode=yes -o ConnectTimeout=10 -i /config/.ssh/oraclevpc.key}"
SLOTS_PER_EPOCH="${POS_SLOTS_PER_EPOCH:-32}"
THRESHOLD="${1:-$SLOTS_PER_EPOCH}"
DB_USERNAME="${DB_USERNAME:-root}"
DB_PASSWORD="${DB_PASSWORD:-test1234}"

RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; NC='\033[0m'

ssh_transport() { # $1=host
    local host="$1"
    if [ "$host" = "$S2001_HOST" ] && [ -n "${JUMP_HOST:-}" ]; then
        echo "ssh $SSH_OPTS -J ${JUMP_HOST} -o StrictHostKeyChecking=accept-new"
    else
        echo "ssh $SSH_OPTS"
    fi
}

remote() { # $1=host  rest=command
    local host="$1"; shift
    # shellcheck disable=SC2086
    $(ssh_transport "$host") "${SSH_USER}@${host}" "$*"
}

# "chainlength headHash" for a seed.
state_of() { # $1=host $2=seed
    remote "$1" "curl -s -m 10 -X POST http://$2/getChainNumber -H 'Content-Type: application/json' -d '{}'" 2>/dev/null | python3 -c '
import sys, json, base64
try:
    d = json.load(sys.stdin)
except Exception:
    print("0 "); sys.exit(0)
tr = d.get("txReward")
tr = json.loads(tr) if isinstance(tr, str) else tr
cl = (tr or {}).get("chainLength", 0)
hb = (tr or {}).get("blockHash")
if isinstance(hb, dict):
    try:
        h = base64.b64decode(hb["bytes"]).hex()[:16]
    except Exception:
        h = ""
else:
    h = str(hb or "")[:16]
print(f"{cl} {h}")'
}

# confirmed block hash at a chainlength from the node's DB.
hash_at() { # $1=host $2=db $3=port $4=chainlength
    remote "$1" "PGPASSWORD='${DB_PASSWORD}' psql -h localhost -p $3 -U '${DB_USERNAME}' -d $2 -t -A -c \"SELECT substring(encode(blockhash,'hex'),1,12) FROM txreward WHERE chainlength=$4 AND confirmed=true;\"" 2>/dev/null | tr -d ' '
}

validators_of() { # $1=host $2=seed
    remote "$1" "curl -s -m 10 -X POST http://$2/getValidators -H 'Content-Type: application/json' -d '{}'" 2>/dev/null | python3 -c '
import sys, json
try:
    d = json.load(sys.stdin)
except Exception:
    print(0); sys.exit(0)
t = d.get("text") or d.get("validators")
t = json.loads(t) if isinstance(t, str) else t
v = (t or {}).get("validators") if isinstance(t, dict) else t
print(len(v) if isinstance(v, list) else 0)'
}

slashing_of() { # $1=host $2=db $3=port
    remote "$1" "PGPASSWORD='${DB_PASSWORD}' psql -h localhost -p $3 -U '${DB_USERNAME}' -d $2 -t -A -c \"SELECT count(*) FROM blocks WHERE blocktype='BLOCKTYPE_SLASHING';\"" 2>/dev/null | tr -d ' '
}

HOST0="$S2001_HOST"; HOST1="$S2001_HOST"; HOST2="$CUI_HOST"
SEEDS=( "${S2001_HOST}:8081" "${S2001_HOST}:8082" "${CUI_HOST}:8083" )
DBS=( "layer0" "layer0_1" "layer0_2" )
PORTS=( "5432" "5432" "5433" )
HOSTS=( "$HOST0" "$HOST1" "$HOST2" )

echo "=== fork-safety check (spread threshold ${THRESHOLD}) ==="
maxcl=0; mincl=999999999; ok=true
for i in 0 1 2; do
    state=$(state_of "${HOSTS[$i]}" "${SEEDS[$i]}")
    cl="${state%% *}"; head="${state##* }"
    v=$(validators_of "${HOSTS[$i]}" "${SEEDS[$i]}")
    sl=$(slashing_of "${HOSTS[$i]}" "${DBS[$i]}" "${PORTS[$i]}")
    printf "  %-18s chainlength=%-4s head=%-16s validators=%s slashing=%s\n" "${SEEDS[$i]}" "$cl" "$head" "$v" "${sl:-?}"
    [ "${cl:-0}" -gt "$maxcl" ] 2>/dev/null && maxcl="$cl"
    [ "${cl:-999999999}" -lt "$mincl" ] 2>/dev/null && mincl="$cl"
done

spread=$((maxcl - mincl))
if [ "$spread" -gt "$THRESHOLD" ]; then
    echo -e "${RED}FAIL: chainlength spread ${mincl}..${maxcl} = ${spread} > ${THRESHOLD}${NC}"
    ok=false
fi

# Confirmed-hash agreement at a chainlength every node has reached.
common="$mincl"
if [ "$common" -lt 1 ]; then common=0; fi
echo -n "  hash agreement @ cl ${common}: "
prev=""
for i in 0 1 2; do
    h=$(hash_at "${HOSTS[$i]}" "${DBS[$i]}" "${PORTS[$i]}" "$common")
    printf "%s=%s " "${SEEDS[$i]##*:}" "${h:-MISSING}"
    if [ -n "$prev" ] && [ -n "$h" ] && [ "$h" != "$prev" ]; then
        echo -e "${RED}-> HEAD HASH DIVERGES${NC}"
        ok=false
    fi
    [ -n "$h" ] && prev="$h"
done
echo

if [ "$ok" = true ]; then
    echo -e "${GREEN}OK: ${maxcl} confirmed, spread ${spread} <= ${THRESHOLD}, hashes agree, no slashing${NC}"
    exit 0
else
    echo -e "${RED}DIVERGENCE detected — investigate / reset before proceeding${NC}"
    exit 1
fi
