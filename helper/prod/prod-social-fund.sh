#!/usr/bin/env bash
# prod-social-fund.sh — fund the L1 SOCIAL chain and make the socialeu server
# pair validators, driven by an exported PQ wallet (v1.json format).
#
# Flow (L1-social never mints bc in genesis; funds arrive ONLY as a vault
# peg-in from L0, chainId SOCIAL — see layers.md §5 and SocialL1Params):
#   preflight : read-only balance gate on the L0 (aborts before ANY send)
#   split     : ensure the wallet owns an exact AMOUNT sat self-UTXO on L0
#   pegin     : lock that UTXO to the vault for chainId SOCIAL (beneficiary =
#               the wallet pubkey) via L0 processPegIn
#   stake     : wait for the socialeu nodes to mint, then stakeDeposit +
#               activateValidator on every SOCIAL_URLS node (needs API_KEY)
#
# Env:
#   WALLET       exported PQ key json (default /home/jcui/validators/v1.json)
#   AMOUNT_BIG   amount in BIG   (default 1000000 = 1m BIG = 1e12 sat)
#   L0_URL       L0 API          (default https://eu1.bigtangle.org)
#   SOCIAL_URLS  space-separated L1 SOCIAL APIs
#                (default https://socialeu1.bigtangle.org https://socialeu2.bigtangle.org)
#   API_KEY      X-Api-Key for stake/activate (required for `stake`)
#
# Prereqs on the deployed nodes:
#   - L0 eu1/eu2 run with bridge enabled + BRIDGE_VAULT_PUBKEY=<wallet pubkey>
#     (prod.sh) so processPegIn validates the lock.
#   - L1 socialeu1/2 run with POS_VALIDATOR_KEY=<wallet key> + the same
#     BRIDGE_VAULT_PUBKEY + ANCHOR_L0_URL=<L0_URL> (prod-social.sh) so the
#     PegInWatcher mints the wrapped amount to the beneficiary.
#
# Usage:
#   prod-social-fund.sh check            preflight gate (read-only)
#   prod-social-fund.sh split            exact-amount self-UTXO
#   prod-social-fund.sh pegin            vault lock for SOCIAL
#   prod-social-fund.sh stake            stake + activate the validators
#   prod-social-fund.sh run              check -> split -> pegin -> stake
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
TOOL_DIR="$(cd "$(dirname "$0")" && pwd)/social"
WALLET="${WALLET:-/home/jcui/validators/v1.json}"
AMOUNT_BIG="${AMOUNT_BIG:-1000000}"
L0_URL="${L0_URL:-https://eu1.bigtangle.org}"
SOCIAL_URLS="${SOCIAL_URLS:-https://socialeu1.bigtangle.org https://socialeu2.bigtangle.org}"
API_KEY="${API_KEY:-}"

log() { echo "[social-fund] $*"; }
die() { echo "[social-fund] FAIL: $*" >&2; exit 1; }

[ -f "$WALLET" ] || die "wallet $WALLET not found"
command -v jq >/dev/null 2>&1 || die "jq required"
command -v python3 >/dev/null 2>&1 || die "python3 required"

keytype="$(jq -r '.keys[0].keyType' "$WALLET")"
[ "$keytype" = "PQ" ] || die "wallet keyType=$keytype — only PQ wallets supported"

# 1 BIG = 10^6 satoshis (NetworkParameters.BIGTANGLE_DECIMAL).
AMOUNT_SAT="$(python3 -c "print(int($AMOUNT_BIG) * 10**6)")"
log "wallet=$WALLET amount=${AMOUNT_BIG} BIG = ${AMOUNT_SAT} sat"
log "L0=$L0_URL"
log "SOCIAL_URLS=$SOCIAL_URLS"
[ -n "$API_KEY" ] || log "WARN: API_KEY unset — stake/activate will fail"

EXEC_JAR="$(ls -t "${ROOT}"/layer0-server/target/layer0-server-*-exec.jar 2>/dev/null | head -1 || true)"
[ -n "$EXEC_JAR" ] || die "no layer0-server exec jar at layer0-server/target/ (run: mvn -q package -DskipTests)"
CACHE="${TMPDIR:-/tmp}/bigtangle-social-fund/cp"
if [ ! -d "${CACHE}/BOOT-INF/classes" ]; then
    log "unpacking ${EXEC_JAR##*/} -> ${CACHE}"
    rm -rf "$(dirname "$CACHE")" && mkdir -p "$(dirname "$CACHE")"
    unzip -q "$EXEC_JAR" -d "$CACHE"
fi
CP="${CACHE}/BOOT-INF/classes:${CACHE}/BOOT-INF/lib/*"
TOOL="${TOOL_DIR}/SocialFundTool.java"

run_tool() { # $1=phase  (rest of args consumed)
    L0_URL="$L0_URL" SOCIAL_URLS="$SOCIAL_URLS" API_KEY="$API_KEY" \
        java -cp "$CP" "$TOOL" "$WALLET" "$1" "$AMOUNT_SAT"
}

case "${1:-run}" in
    check)
        log "preflight gate (no funds moved)"
        run_tool preflight
        ;;
    split)
        run_tool split
        ;;
    pegin)
        run_tool pegin
        ;;
    stake)
        run_tool stake
        ;;
    run)
        log "phase 1/4 preflight"
        run_tool preflight
        log "phase 2/4 split"
        run_tool split
        log "phase 3/4 pegin"
        run_tool pegin
        log "phase 4/4 stake+activate"
        run_tool stake
        log "done — verify with https://${SOCIAL_URLS%% *}/getValidators"
        ;;
    *)
        sed -n '2,24p' "$0" | sed 's/^# \{0,1\}//'
        exit 1
        ;;
esac
