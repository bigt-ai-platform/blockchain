#!/usr/bin/env bash
# genesis-payout.sh — distribute the L0 genesis supply from the 2-of-3 ceremony
# multisig to a GenesisOutput CSV (address,pubkey,value) on the live chain.
#
# Flow (prod genesis mints the total to the ceremony multisig because
# GENESIS_CSV is empty at deploy — see prod.sh; payout replaces CSV-genesis):
#   genesis     : print the locally recomputed genesis hash/script/value
#                 (offline ceremony record — compare with the node after reset)
#   dryrun      : build+sign+verify batch 0 offline (nothing sent) — the
#                 consensus-critical crypto proof before ceremony day
#   preflight   : read-only gates (node genesis match, CSV sum + fees fit,
#                 finality/maturity gate, already-funded report)
#   payout      : pay all rows in batches, progress-tracked, resumable
#   verify      : every CSV row funded (exit 0 iff complete)
#   resume <tx> : print CHANGE_OUTPOINT for a submitted batch tx (recovery when
#                 the progress file is lost — keep the payout console log)
#
# Env:
#   L0_URL         L0 API (default https://eu1.bigtangle.org)
#   GENESIS_CSV    distribution CSV (default helper/prodsim/genesis/GenesisOutput.csv)
#   GENESIS_EXCLUDE_CSV addresses to skip, one per line (default
#                  GenesisOutputExclude.csv next to GENESIS_CSV; missing file
#                  = no exclusions; empty value = no exclusions)
#   GENESIS_KEYDIR ceremony dir (default /home/jcui/validators — holds
#                  genesis-{0,1,2}.env with GENESIS_SEED= and genesis-2of3.env
#                  with GENESIS_PUBKEYS=)
#   GENESIS_SEEDS  "seed0,seed1" — any 2 of the 3 seeds (default: read from
#                  genesis-0.env + genesis-1.env; never echoed with set -x off)
#   BATCH_ROWS     outputs per tx (default 200)
#   PROGRESS_FILE  resume state (default <KEYDIR>/genesis-payout.progress)
#   CHANGE_OUTPOINT "blockHash:txHash:index:value" manual resume (from resume)
#   MATURITY_DEPTH coinbase-maturity/finality gate (default 100)
#
# Ceremony order: prod.sh reset/up -> genesis (record) -> preflight ->
# payout -> verify -> destroy the genesis seeds.
#
# Usage:
#   genesis-payout.sh genesis|preflight|payout|verify|resume <txHash>
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
TOOL_DIR="$(cd "$(dirname "$0")" && pwd)/validators"
KEYDIR="${GENESIS_KEYDIR:-/home/jcui/validators}"
L0_URL="${L0_URL:-https://eu1.bigtangle.org}"
GENESIS_CSV="${GENESIS_CSV:-${ROOT}/helper/prodsim/genesis/GenesisOutput.csv}"
BATCH_ROWS="${BATCH_ROWS:-200}"
PROGRESS_FILE="${PROGRESS_FILE:-${KEYDIR}/genesis-payout.progress}"

log() { echo "[genesis-payout] $*"; }
die() { echo "[genesis-payout] FAIL: $*" >&2; exit 1; }

[ -f "$GENESIS_CSV" ] || die "CSV not found: $GENESIS_CSV"
[ -f "${KEYDIR}/genesis-2of3.env" ] || die "ceremony record not found: ${KEYDIR}/genesis-2of3.env"

# shellcheck disable=SC1090
GENESIS_PUBKEYS="$(grep '^GENESIS_PUBKEYS=' "${KEYDIR}/genesis-2of3.env" | cut -d= -f2-)"
[ -n "$GENESIS_PUBKEYS" ] || die "GENESIS_PUBKEYS missing in genesis-2of3.env"

if [ -z "${GENESIS_SEEDS:-}" ]; then
    # Default signers: keys 0 and 1 (any 2 of 3 work). Seeds travel via env,
    # never CLI args (visible in ps).
    S0="$(grep '^GENESIS_SEED=' "${KEYDIR}/genesis-0.env" | cut -d= -f2-)"
    S1="$(grep '^GENESIS_SEED=' "${KEYDIR}/genesis-1.env" | cut -d= -f2-)"
    [ -n "$S0" ] && [ -n "$S1" ] || die "cannot read seeds from ${KEYDIR}/genesis-{0,1}.env"
    GENESIS_SEEDS="${S0},${S1}"
fi

EXEC_JAR="$(ls "${ROOT}"/layer0-server/target/layer0-server-*-exec.jar 2>/dev/null | sort -V | tail -1 || true)"
[ -n "$EXEC_JAR" ] || die "no layer0-server exec jar at layer0-server/target/ (run: mvn -q package -DskipTests)"
CACHE="${TMPDIR:-/tmp}/bigtangle-genesis-payout/cp-$(basename "$EXEC_JAR" .jar)"
if [ ! -d "${CACHE}/BOOT-INF/classes" ]; then
    log "unpacking ${EXEC_JAR##*/} -> ${CACHE}"
    rm -rf "$(dirname "$CACHE")" && mkdir -p "$(dirname "$CACHE")"
    unzip -q "$EXEC_JAR" -d "$CACHE"
fi
CP="${CACHE}/BOOT-INF/classes:${CACHE}/BOOT-INF/lib/*"
TOOL="${TOOL_DIR}/GenesisPayoutTool.java"

case "${1:-}" in
    genesis|preflight|dryrun|payout|verify)
        L0_URL="$L0_URL" GENESIS_CSV="$GENESIS_CSV" GENESIS_PUBKEYS="$GENESIS_PUBKEYS" \
        GENESIS_SEEDS="$GENESIS_SEEDS" BATCH_ROWS="$BATCH_ROWS" PROGRESS_FILE="$PROGRESS_FILE" \
        CHANGE_OUTPOINT="${CHANGE_OUTPOINT:-}" MATURITY_DEPTH="${MATURITY_DEPTH:-100}" \
        FEE_SAT="${FEE_SAT:-}" \
        java -cp "$CP" "$TOOL" "$1"
        ;;
    resume)
        [ -n "${2:-}" ] || die "usage: genesis-payout.sh resume <batchTxHash>"
        L0_URL="$L0_URL" GENESIS_CSV="$GENESIS_CSV" GENESIS_PUBKEYS="$GENESIS_PUBKEYS" \
        GENESIS_SEEDS="$GENESIS_SEEDS" BATCH_ROWS="$BATCH_ROWS" PROGRESS_FILE="$PROGRESS_FILE" \
        java -cp "$CP" "$TOOL" resume "$2"
        ;;
    *)
        awk 'NR>=2 && /^set -euo pipefail/{exit} NR>=2' "$0" | sed 's/^# \{0,1\}//'
        exit 1
        ;;
esac
