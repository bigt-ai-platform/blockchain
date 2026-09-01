#!/usr/bin/env bash
# stake_stagger.sh — stake+activate the mesh ONE node at a time (delay between)
# to avoid the simultaneous-activation storm that split the 10-node mesh at
# boot (all validators activating within a 2-min window while nodes had
# inconsistent validator-set views -> competing first blocks -> no finality).
# Node i is staked+activated, then we wait DELAY_S before the next so each
# deposit/activation propagates through kafka and confirms before the next
# node joins the active set.
set -euo pipefail

WORKDIR="${WORKDIR:-/tmp/bt4test}"
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
NNODES="${NNODES:-10}"
DELAY_S="${DELAY_S:-75}"

for i in $(seq 0 $((NNODES - 1))); do
    echo "[stagger] staking node-${i}"
    ( cd "${WORKDIR}/node-${i}" && bash -c "
        set -euo pipefail
        source ../common.env; source ./validator.env
        source ../validator_common.sh
        wait_api
        wait_balance \${STAKE_AMOUNT}
        stake_validator >/dev/null; sleep 2; activate_validator >/dev/null" ) 2>&1 | tail -2
    echo "[stagger] node-${i} staked+activated"
    [ "$i" -lt $((NNODES - 1)) ] && { echo "[stagger] delay ${DELAY_S}s"; sleep "${DELAY_S}"; }
done
echo "[stagger] all ${NNODES} nodes staked"
