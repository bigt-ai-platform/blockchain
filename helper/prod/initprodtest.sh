#!/usr/bin/env bash
# initprodtest.sh — verify the genesis-key init flow end to end.
#
#   check   : offline suite, no node needed (run anytime — run before every
#             ceremony): key files + perms, seed<->pub correspondence,
#             genesisPub match, genesis record match, batch-0 build+sign+verify
#             proof (dryrun), sum/exclusion guards incl. the over-sum negative
#             test, prod.sh key-mode defaults. Safe: sends nothing.
#   e2e --yes : live ceremony on L0_URL (post-reset, MOVES REAL FUNDS):
#             preflight -> payout -> verify, logged, strict exits.
#
# Env (both modes):
#   KEYDIR      ceremony dir (default /home/jcui/validators)
#   GENESIS_CSV distribution CSV (default helper/prodsim/genesis/GenesisOutput.csv)
# Env (e2e): L0_URL (default https://eu1.bigtangle.org), plus whatever
# genesis-payout.sh accepts (BATCH_ROWS, PROGRESS_FILE, ...).
#
# Usage:
#   initprodtest.sh check
#   initprodtest.sh e2e --yes
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
KEYDIR="${GENESIS_KEYDIR:-/home/jcui/validators}"
GENESIS_CSV="${GENESIS_CSV:-${ROOT}/helper/prodsim/genesis/GenesisOutput.csv}"
PASS=0
FAIL=0

log() { echo "[initprodtest] $*"; }
pass() { PASS=$((PASS + 1)); echo "[initprodtest] PASS: $*"; }
fail() { FAIL=$((FAIL + 1)); echo "[initprodtest] FAIL: $*" >&2; }

need_cmd() { command -v "$1" >/dev/null 2>&1 || { fail "missing command: $1"; return 1; }; }

# ---- offline check ------------------------------------------------------------
check() {
    need_cmd java && need_cmd unzip || { log "check done: PASS=$PASS FAIL=$FAIL"; return 1; }
    [ -f "${ROOT}/bigtangle-core/target/classes/net/bigtangle/tools/ValidatorKeyTool.class" ] \
        || { fail "ValidatorKeyTool.class missing (run: mvn -q -o -pl bigtangle-core compile)"; \
            log "check done: PASS=$PASS FAIL=$FAIL"; return 1; }

    # T1: ceremony files + permissions (seeds 600, never committed).
    for i in 0 1 2; do
        f="${KEYDIR}/genesis-${i}.env"
        [ -f "$f" ] || { fail "missing $f"; continue; }
        [ "$(stat -c %a "$f")" = "600" ] || fail "$f perms $(stat -c %a "$f") != 600"
        grep -q '^GENESIS_SEED=[0-9a-f]\{64\}$' "$f" || fail "$f has no 32-byte GENESIS_SEED"
        grep -q '^GENESIS_PUBKEY=050101010a' "$f" || fail "$f has no PQ GENESIS_PUBKEY"
    done
    [ -f "${KEYDIR}/genesis-2of3.env" ] || fail "missing genesis-2of3.env"
    grep -q '^GENESIS_PUBKEYS=' "${KEYDIR}/genesis-2of3.env" || fail "genesis-2of3.env has no GENESIS_PUBKEYS"
    grep -q '^EXPECTED_GENESIS=[0-9a-f]\{64\}$' "${KEYDIR}/genesis-2of3.env" \
        || fail "genesis-2of3.env has no EXPECTED_GENESIS"
    # No seed material inside the repo, ever.
    if grep -r "^GENESIS_SEED=" "${ROOT}/helper" "${ROOT}/bigtangle-core/src" 2>/dev/null \
        | grep -v genesis-payout.sh | grep -v initprodtest.sh | grep -q .; then
        fail "seed-looking GENESIS_SEED= value found inside the repo"
    else
        pass "no seed material in repo"
    fi

    # Tool classpath: unpacked exec jar (shared cache with genesis-payout.sh).
    EXEC_JAR="$(ls "${ROOT}"/layer0-server/target/layer0-server-*-exec.jar 2>/dev/null | sort -V | tail -1 || true)"
    [ -n "$EXEC_JAR" ] || { fail "no layer0-server exec jar (run: mvn -q package -DskipTests)"; \
        log "check done: PASS=$PASS FAIL=$FAIL"; return 1; }
    CP_CACHE="${TMPDIR:-/tmp}/bigtangle-genesis-payout/cp-$(basename "$EXEC_JAR" .jar)"
    if [ ! -d "${CP_CACHE}/BOOT-INF/classes" ]; then
        rm -rf "${CP_CACHE%/*}" && mkdir -p "${CP_CACHE%/*}"
        unzip -q "$EXEC_JAR" -d "$CP_CACHE" || { fail "cannot unpack $EXEC_JAR"; \
            log "check done: PASS=$PASS FAIL=$FAIL"; return 1; }
    fi
    CP="${CP_CACHE}/BOOT-INF/classes:${CP_CACHE}/BOOT-INF/lib/*"

    # T2: each seed derives its recorded pubkey (proves the files are
    # self-consistent; prints only match results, never seeds).
    for i in 0 1 2; do
        seed="$(grep '^GENESIS_SEED=' "${KEYDIR}/genesis-${i}.env" | cut -d= -f2-)"
        want="$(grep '^GENESIS_PUBKEY=' "${KEYDIR}/genesis-${i}.env" | cut -d= -f2-)"
        got="$(java -cp "$CP" net.bigtangle.tools.ValidatorKeyTool pubkey "$seed" 2>/dev/null \
            | grep '^VALIDATOR_PUBKEY=' | cut -d= -f2-)"
        if [ -n "$got" ] && [ "$got" = "$want" ]; then
            pass "genesis-$i seed derives recorded pubkey"
        else
            fail "genesis-$i seed/pubkey mismatch"
        fi
    done

    # T3: tool-accepted genesisPub + expected hash match the public record
    # (setup() aborts on pubkey mismatch, so a clean `genesis` run proves it).
    rec="$(grep '^EXPECTED_GENESIS=' "${KEYDIR}/genesis-2of3.env" | cut -d= -f2-)"
    out="$("${ROOT}/helper/prod/genesis-payout.sh" genesis 2>/dev/null)" \
        || { fail "genesis-payout.sh genesis failed"; out=""; }
    echo "$out" | grep -q "EXPECTED_GENESIS=${rec}" \
        && pass "recomputed genesis matches ceremony record" \
        || fail "genesis hash mismatch (record $rec)"
    echo "$out" | grep -q "GENESIS_VALUE_SAT=100000000000000000" \
        && pass "genesis value = 1e17 sat" \
        || fail "genesis value unexpected"
    echo "$out" | grep -q "GENESIS_OUTPUTS=1" \
        && pass "single genesis output" \
        || fail "genesis output count unexpected"

    # T4: dryrun on a tiny CSV — full build+sign+verify proof, offline.
    A0="$(grep '^GENESIS_ADDRESS=' "${KEYDIR}/genesis-0.env" | cut -d= -f2-)"
    A1="$(grep '^GENESIS_ADDRESS=' "${KEYDIR}/genesis-1.env" | cut -d= -f2-)"
    A2="$(grep '^GENESIS_ADDRESS=' "${KEYDIR}/genesis-2.env" | cut -d= -f2-)"
    tcsv="$(mktemp /tmp/initprodtest.XXXXXX.csv)"
    printf 'address,pubkey,value\n%s,,1000000\n%s,,2000000\n%s,,3000000\n' "$A0" "$A1" "$A2" > "$tcsv"
    dout="$(GENESIS_CSV="$tcsv" "${ROOT}/helper/prod/genesis-payout.sh" dryrun 2>&1)" \
        && dst=0 || dst=$?
    rm -f "$tcsv"
    if [ "$dst" -eq 0 ] \
        && echo "$dout" | grep -q "DRYRUN_SIG0_VERIFY=true" \
        && echo "$dout" | grep -q "DRYRUN_SIG1_VERIFY=true" \
        && echo "$dout" | grep -q "DRYRUN_CONSERVATION_OK=" \
        && echo "$dout" | grep -q "DRYRUN_OK"; then
        pass "dryrun: 2-of-3 batch-0 verifies (outpoint/conservation/change/sigs)"
    else
        fail "dryrun failed (exit $dst)"
        echo "$dout" | tail -5 >&2
    fi

    # T5: negative test — the default prodsim snapshot cannot fit the genesis
    # (also exercises the real exclusion file: EXCLUDED_ROWS accounting).
    nout="$(GENESIS_CSV="$GENESIS_CSV" "${ROOT}/helper/prod/genesis-payout.sh" dryrun 2>&1)" \
        && nst=0 || nst=$?
    erows="$(echo "$nout" | grep '^EXCLUDED_ROWS=' | cut -d= -f2- | awk '{print $1}')"
    [ -n "$erows" ] || erows=0
    prows="$(echo "$nout" | grep '^PAY_ROWS=' | cut -d= -f2-)"
    rrows="$(echo "$nout" | grep '^CSV=' | sed 's/.*ROWS=//')"
    if [ "$nst" -eq 2 ] && echo "$nout" | grep -q "exceeds the genesis value" \
        && [ -n "$erows" ] && [ -n "$prows" ] && [ -n "$rrows" ] \
        && [ "$prows" = "$((rrows - erows))" ]; then
        pass "over-sum guard aborts (PAY $prows = ROWS $rrows - EXCLUDED $erows)"
    else
        fail "over-sum negative test behaved unexpectedly (exit $nst)"
        echo "$nout" | tail -8 >&2
    fi

    # T6: prod.sh is in key mode and parses.
    bash -n "${ROOT}/helper/prod/prod.sh" && pass "prod.sh syntax OK" || fail "prod.sh syntax"
    grep -q 'GENESIS_CSV="${GENESIS_CSV:-}"' "${ROOT}/helper/prod/prod.sh" \
        && pass "prod.sh GENESIS_CSV defaults to empty (key mode)" \
        || fail "prod.sh GENESIS_CSV default is not empty"
    usage="$("${ROOT}/helper/prod/prod.sh" 2>&1 || true)" # no-arg usage exits 1 by design
    echo "$usage" | grep -q "prod.sh — deploy" \
        && pass "prod.sh usage prints" \
        || fail "prod.sh usage broken"

    log "check done: PASS=$PASS FAIL=$FAIL"
    [ "$FAIL" -eq 0 ]
}

# ---- live e2e (post-reset ceremony) --------------------------------------------
e2e() {
    [ "${1:-}" = "--yes" ] || { echo "usage: initprodtest.sh e2e --yes   (MOVES REAL FUNDS)" >&2; exit 2; }
    need_cmd java
    LOG="${KEYDIR}/genesis-ceremony.log"
    {
        log "e2e start L0_URL=${L0_URL:-https://eu1.bigtangle.org} CSV=$GENESIS_CSV"
        log "phase 1/3 preflight"
        L0_URL="${L0_URL:-https://eu1.bigtangle.org}" GENESIS_CSV="$GENESIS_CSV" \
            "${ROOT}/helper/prod/genesis-payout.sh" preflight
        log "phase 2/3 payout"
        L0_URL="${L0_URL:-https://eu1.bigtangle.org}" GENESIS_CSV="$GENESIS_CSV" \
            "${ROOT}/helper/prod/genesis-payout.sh" payout
        log "phase 3/3 verify"
        L0_URL="${L0_URL:-https://eu1.bigtangle.org}" GENESIS_CSV="$GENESIS_CSV" \
            "${ROOT}/helper/prod/genesis-payout.sh" verify
        log "E2E PASS — destroy the genesis seeds"
    } 2>&1 | tee "$LOG"
    [ "${PIPESTATUS[0]}" -eq 0 ] || exit "${PIPESTATUS[0]}"
    log "e2e log: $LOG"
}

case "${1:-check}" in
    check) check ;;
    e2e) shift; e2e "$@" ;;
    *) awk 'NR>=2 && /^set -euo pipefail/{exit} NR>=2' "$0" | sed 's/^# \{0,1\}//'; exit 1 ;;
esac
