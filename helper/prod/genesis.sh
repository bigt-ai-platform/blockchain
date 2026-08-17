#!/usr/bin/env bash
# Snapshot the legacy chain's BIG balances into GenesisOutput.csv.
#
# Reads the confirmed, unspent outputs of the legacy PostgreSQL database
# (table `outputs`, tokenid 'bc') and aggregates them per address. The
# resulting CSV is consumed by layer-0 genesis creation
# (UtilGeneseBlock.createGenesis via bigtangle.genesis.csv / BIGTANGLE_GENESIS_CSV).
#
# Usage:
#   DB_HOST=1.2.3.4 DB_NAME=legacy DB_USER=root DB_PASSWORD=secret ./genesis.sh
#
# Output: GenesisOutput.csv  (columns: address,pubkey,value)
set -euo pipefail

cd "$(dirname "$0")"

# ---- Connection (override via env) ----------------------------------------
DB_HOST="${DB_HOST:-localhost}"
DB_PORT="${DB_PORT:-5432}"
DB_USER="${DB_USER:-root}"
DB_PASSWORD="${DB_PASSWORD:-test1234}"
DB_NAME="${DB_NAME:-layer0}"
TOKENID="${TOKENID:-bc}"
OUT_CSV="${OUT_CSV:-GenesisOutput.csv}"

# Total BIG supply in satoshis: BigtangleCoinTotal = 10^(11 + 6) = 10^17
TOTAL_SUPPLY="${TOTAL_SUPPLY:-100000000000000000}"

psql_cmd() {
    PGPASSWORD="${DB_PASSWORD}" psql -h "${DB_HOST}" -p "${DB_PORT}" -U "${DB_USER}" -d "${DB_NAME}" "$@"
}

echo "Snapshotting ${DB_HOST}:${DB_PORT}/${DB_NAME} (tokenid=${TOKENID}) ..."

# Aggregate confirmed, unspent BIG outputs per address. Order by address so the
# CSV (and thus the genesis hash) is deterministic. The pubkey column is emitted
# empty (legacy rows carry no pubkey); the CSV consumer
# (UtilGeneseBlock.createGenesis) expects address,pubkey,value.
psql_cmd -A -F, -t -c "
SELECT toaddress, '' AS pubkey, SUM(coinvalue)
FROM outputs
WHERE confirmed = true
  AND spent = false
  AND tokenid = '${TOKENID}'
  AND toaddress IS NOT NULL
  AND toaddress <> ''
GROUP BY toaddress
ORDER BY toaddress;
" > /tmp/genesis_rows.csv

# Exact integer total (computed in PostgreSQL, not awk's float): the genesis
# supply check must never fail on a rounding artefact.
total=$(psql_cmd -A -t -c "
SELECT COALESCE(SUM(coinvalue), 0)
FROM outputs
WHERE confirmed = true
  AND spent = false
  AND tokenid = '${TOKENID}';
")

# Build the CSV: header (address,pubkey,value) + data rows (pubkey empty).
{
    echo "address,pubkey,value"
    cat /tmp/genesis_rows.csv
} > "${OUT_CSV}"

rows=$(($(wc -l < "${OUT_CSV}") - 1))
sum="${total:-0}"

echo "Wrote ${OUT_CSV} (${rows} addresses, total ${sum} satoshis)"

if [ "${sum}" != "${TOTAL_SUPPLY}" ]; then
    echo "WARNING: minted total ${sum} != expected supply ${TOTAL_SUPPLY}" >&2
    echo "         reconcile the legacy DB before launching layer 0" >&2
    exit 1
fi

echo "OK: total matches BigtangleCoinTotal (${TOTAL_SUPPLY})."
