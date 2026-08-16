#!/usr/bin/env bash
# Node 2 — create DB, start layer0-server + layer0-mcmc, fund/stake/activate.
set -euo pipefail
cd "$(dirname "$0")"

# shellcheck source=../common.env
source ../common.env
# shellcheck source=validator.env
source ./validator.env
# shellcheck source=../validator_common.sh
source ../validator_common.sh

run_all "$@"
