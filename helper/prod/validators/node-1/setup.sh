#!/usr/bin/env bash
# Node 1 — phased setup: setup.sh <server|stake|verify>. Run the
# phases in order across ALL nodes: server (all) → stake (all) → verify.
# Validator duties (beacon proposals) run on the layer0-server itself.
set -euo pipefail
cd "$(dirname "$0")"

# shellcheck source=../common.env
source ../common.env
# shellcheck source=validator.env
source ./validator.env
# shellcheck source=../validator_common.sh
source ../validator_common.sh

run_phase "${1:-server}"
