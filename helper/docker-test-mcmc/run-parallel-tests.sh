#!/usr/bin/env bash
# run-parallel-tests.sh
# Builds the mcmc-test-runner image, starts infrastructure and all 4 test
# groups in parallel, waits for every group to finish, then reports results.
#
# Usage:
#   cd <repo-root>
#   ./helper/docker-test-mcmc/run-parallel-tests.sh
#
# Options:
#   --no-build   Skip docker image build (use cached image)
#   --groups N   Run only groups 1..N  (default: 4)
#   --single     Run all 16 tests in a single container against one postgres

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
COMPOSE_FILE="${SCRIPT_DIR}/docker-compose.yml"
COMPOSE="docker compose -f ${COMPOSE_FILE}"

NO_BUILD=false
NUM_GROUPS=4
SINGLE=false

for arg in "$@"; do
  case $arg in
    --no-build) NO_BUILD=true ;;
    --groups)   shift; NUM_GROUPS=$1 ;;
    --single)   SINGLE=true ;;
  esac
done

if [ "${SINGLE}" = true ]; then
  # ── Single-container mode ──────────────────────────────────────────────────
  if [ "${NO_BUILD}" = false ]; then
    echo "==> Building mcmc-test-runner image …"
    ${COMPOSE} build test-all 2>&1 | tail -5
    echo "    Build complete."
  fi

  echo "==> Cleaning up previous containers …"
  ${COMPOSE} down -v --remove-orphans 2>/dev/null || true

  echo "==> Starting infrastructure (minio + postgres-1) …"
  ${COMPOSE} up -d minio postgres-1

  echo "    Waiting for postgres-1 …"
  until ${COMPOSE} exec -T postgres-1 pg_isready -U root >/dev/null 2>&1; do
    sleep 2
  done
  echo "    postgres-1 ready."

  echo "==> Starting test-all container …"
  ${COMPOSE} up -d test-all

  echo "    Waiting for mcmc-test-all …"
  EXIT_CODE=$(docker wait mcmc-test-all 2>/dev/null || echo 1)

  # Collect reports
  REPORT_DIR="${SCRIPT_DIR}/test-reports-$(date +%Y%m%d-%H%M%S)"
  mkdir -p "${REPORT_DIR}/test-all"
  docker cp "mcmc-test-all:/build/bigtangle-mcmc/target/surefire-reports/." \
    "${REPORT_DIR}/test-all/" 2>/dev/null || true
  docker cp "mcmc-test-all:/test-output/." \
    "${REPORT_DIR}/test-all/" 2>/dev/null || true

  echo ""
  echo "════════════════════════════════════════════════"
  echo " MCMC single-container test result"
  echo "════════════════════════════════════════════════"
  if [ "${EXIT_CODE}" -eq 0 ]; then
    echo "  test-all: PASSED"
  else
    echo "  test-all: FAILED (exit ${EXIT_CODE})"
  fi
  echo "────────────────────────────────────────────────"
  echo "  Reports saved to: ${REPORT_DIR}"
  echo "════════════════════════════════════════════════"

  echo "==> Stopping all containers …"
  ${COMPOSE} down -v 2>/dev/null || true
  exit ${EXIT_CODE}
fi

# ── Parallel mode (default) ──────────────────────────────────────────────────
# ── 1. Build ─────────────────────────────────────────────────────────────────
if [ "${NO_BUILD}" = false ]; then
  echo "==> Building mcmc-test-runner image …"
  ${COMPOSE} build test-group-1 2>&1 | tail -5
  echo "    Build complete."
fi

# ── 2. Tear down any leftovers from previous runs ────────────────────────────
echo "==> Cleaning up previous containers …"
${COMPOSE} down -v --remove-orphans 2>/dev/null || true

# ── 3. Start infrastructure (postgres x4 + minio) ────────────────────────────
echo "==> Starting infrastructure …"
${COMPOSE} up -d minio postgres-1 postgres-2 postgres-3 postgres-4

echo "    Waiting for databases to be ready …"
for i in 1 2 3 4; do
  until ${COMPOSE} exec -T "postgres-${i}" pg_isready -U root >/dev/null 2>&1; do
    sleep 2
  done
  echo "    postgres-${i} ready."
done

# ── 4. Launch all test groups in parallel ─────────────────────────────────────
unset GROUPS
GROUPS=()
for i in $(seq 1 "${NUM_GROUPS}"); do
  GROUPS+=("test-group-${i}")
done

echo "==> Launching ${NUM_GROUPS} test group(s) in parallel: ${GROUPS[*]}"
${COMPOSE} up -d "${GROUPS[@]}"

# ── 5. Wait for each group and collect exit codes ─────────────────────────────
declare -A EXIT_CODES
for group in "${GROUPS[@]}"; do
  container="${group/test-/mcmc-test-}"   # test-group-1 → mcmc-test-group-1
  echo "    Waiting for ${container} …"
  EXIT_CODES[${group}]=$(docker wait "${container}" 2>/dev/null || echo 1)
done

# ── 6. Collect surefire XML reports ──────────────────────────────────────────
REPORT_DIR="${SCRIPT_DIR}/test-reports-$(date +%Y%m%d-%H%M%S)"
mkdir -p "${REPORT_DIR}"

for group in "${GROUPS[@]}"; do
  container="${group/test-/mcmc-test-}"
  OUT_DIR="${REPORT_DIR}/${group}"
  mkdir -p "${OUT_DIR}"
  # Copy surefire XML reports out of the container
  docker cp "${container}:/build/bigtangle-mcmc/target/surefire-reports/." \
    "${OUT_DIR}/" 2>/dev/null || echo "    (no surefire reports for ${group})"
  # Copy test log
  docker cp "${container}:/test-output/." \
    "${OUT_DIR}/" 2>/dev/null || true
done

# ── 7. Print summary ─────────────────────────────────────────────────────────
echo ""
echo "════════════════════════════════════════════════"
echo " MCMC parallel test results"
echo "════════════════════════════════════════════════"
OVERALL=0
for group in "${GROUPS[@]}"; do
  code="${EXIT_CODES[${group}]}"
  if [ "${code}" -eq 0 ]; then
    status="PASSED"
  else
    status="FAILED (exit ${code})"
    OVERALL=1
  fi
  echo "  ${group}: ${status}"
done
echo "────────────────────────────────────────────────"
echo "  Reports saved to: ${REPORT_DIR}"
echo "════════════════════════════════════════════════"

# ── 8. Tear down ─────────────────────────────────────────────────────────────
echo ""
echo "==> Stopping all containers …"
${COMPOSE} down -v 2>/dev/null || true

exit ${OVERALL}
