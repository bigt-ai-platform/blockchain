#!/usr/bin/env bash
# prodtoken.sh — list every address holding the "bc" token on the prod BIG
# node with the summed balance for that address.
#
# Mirrors WalletService.searchTotalNoSave(): sums the raw UTXO value per
# address, then formats each total using the token decimals the same way
#   new BigDecimal(MonetaryFormat.FIAT.format(total, decimals).trim())
# does (BIGTANGLE_DECIMAL = 6, minDecimals 0, trailing zeros trimmed).
#
# The prod node's outputsOfTokenid API cannot return the full UTXO set for
# "bc" (~2.8M outputs): Jackson blows its 2GB response limit while serializing
# (errorcode 100). So instead of the API this script aggregates directly in
# the node's MySQL database (the deployed node exposes it via docker, e.g.
# container bigtangle-mysql). coinvalue is BigInteger.toByteArray() (signed
# big-endian); bc values never exceed 8 bytes so CONV(HEX(coinvalue),16,10)
# decodes them exactly.
#
# Usage:
#   ./prodtoken.sh [tokenid]       # default tokenid: bc
# Env overrides:
#   SSH_HOST        node host, default p.bigtangle.org (s1001)
#   SSH_USER        default root
#   SSH_KEY         default ~/.ssh/id_rsa
#   MYSQL_CONTAINER docker mysql container, default bigtangle-mysql
#   MYSQL_USER      default root
#   MYSQL_PASSWORD  default test1234
#   DB_NAME         default info
#   DECIMALS        token decimals, default 6 (BIGTANGLE_DECIMAL)
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
SSH_HOST="${SSH_HOST:-p.bigtangle.org}"
SSH_USER="${SSH_USER:-root}"
SSH_KEY="${SSH_KEY:-$HOME/.ssh/id_rsa}"
MYSQL_CONTAINER="${MYSQL_CONTAINER:-bigtangle-mysql}"
MYSQL_USER="${MYSQL_USER:-root}"
MYSQL_PASSWORD="${MYSQL_PASSWORD:-test1234}"
DB_NAME="${DB_NAME:-info}"
TOKENID="${1:-bc}"
DECIMALS="${DECIMALS:-6}"

SSH_OPTS="-o BatchMode=yes -o ConnectTimeout=15 -o StrictHostKeyChecking=accept-new -i $SSH_KEY"

echo "Querying $SSH_HOST:$DB_NAME for tokenid=$TOKENID (decimals=$DECIMALS)" >&2

tmp=$(mktemp)
trap 'rm -f "$tmp"' EXIT
ssh $SSH_OPTS "${SSH_USER}@${SSH_HOST}" \
  "docker exec $MYSQL_CONTAINER mysql -u$MYSQL_USER -p$MYSQL_PASSWORD -N -e \"USE $DB_NAME; SELECT toaddress, CONV(HEX(coinvalue),16,10) FROM outputs WHERE tokenid='$TOKENID' AND spent=0 AND confirmed=1;\"" 2>/dev/null > "$tmp"

python3 - "$DECIMALS" "$TOKENID" <<'PYEOF' "$tmp"
import sys

dec = int(sys.argv[1])
tokenid = sys.argv[2]

totals = {}
with open(sys.argv[3]) as f:
    for line in f:
        line = line.rstrip("\n")
        if not line:
            continue
        addr, raw = line.split("\t")
        if raw.isdigit():
            totals[addr] = totals.get(addr, 0) + int(raw)

def fmt(raw, d):
    numbers, rem = divmod(raw, 10 ** d)
    if rem == 0:
        return str(numbers)
    return "%s.%s" % (numbers, str(rem).rjust(d, "0").rstrip("0"))

if not totals:
    print("(no unspent confirmed outputs for tokenid=%s)" % tokenid)
    sys.exit(0)

grand = 0
print("%-60s %20s  %s" % ("address", "amount_raw", "amount"))
for addr in sorted(totals):
    raw = totals[addr]
    grand += raw
    print("%-60s %20d  %s" % (addr, raw, fmt(raw, dec)))
print("%-60s %20d  %s" % ("TOTAL", grand, fmt(grand, dec)))
PYEOF
