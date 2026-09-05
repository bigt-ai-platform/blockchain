#!/usr/bin/env bash
# prod-social.sh — deploy l1-social-server on s1001 + s2001 (bigt.ai).
#
# One container per host (--network host), each with its own SOCIAL-chain
# database on the host's local postgres. The instances sync cross-layer via
# the local L0 requester and stream via the 2 prod Kafka mirrors:
#   kafkaeu1.bigtangle.org, kafkaeu2.bigtangle.org.
#
# Public API per host (DNS A records): Caddy terminates TLS on 80/443 and
# reverse-proxies to the local server bound on 127.0.0.1:SOCIAL_PORT:
#   s1001.bigt.ai  →  https://socialeu1.bigtangle.org/
#   s2001.bigt.ai  →  https://socialeu2.bigtangle.org/
# Requires the system Caddy on each host (main Caddyfile imports
# Caddyfile.d/*.caddy, reload via `systemctl reload caddy`).
#
# Usage:
#   prod-social.sh up        pull image, ensure DB, (re)start containers, verify API
#   prod-social.sh status    container state + API health per host
#   prod-social.sh logs [N]  tail container logs (default 50 lines)
#   prod-social.sh down      stop + remove containers (databases kept)
#
# Env overrides:
#   SSH_USER SSH_OPTS JUMP_HOST, SOCIAL_HOSTS (default "s1001.bigt.ai,s2001.bigt.ai"),
#   SOCIAL_ADVERTISED (default "socialeu1.bigtangle.org,socialeu2.bigtangle.org",
#     same order as SOCIAL_HOSTS),
#   SOCIAL_IMAGE (default ghcr.io/bigt-ai-platform/l1-social-server),
#   IMAGE_TAG (default latest versioned GH tag, e.g. 0.6.3 — never `latest`),
#   SOCIAL_PORT (default 8091), SOCIAL_BIND (default 127.0.0.1 — the app is
#   only reachable through Caddy; set 0.0.0.0 only when no Caddy is present),
#   CADDY_SITES_DIR (default /etc/caddy/Caddyfile.d — where per-domain site
#   files are dropped; empty disables the Caddy step),
#   SERVER_NET (default Mainnet), DB_NAME (default social — the yml default
#     `payment` is a copy-paste; always override so a future payment server on
#     the same postgres cannot collide), DB_PORT (default 5432),
#   DB_USERNAME DB_PASSWORD (default root/test1234 — override in prod),
#   PG_CONTAINERS (optional comma list parallel to SOCIAL_HOSTS pinning the
#     postgres container per host; default auto-detects the first postgres
#     container, which is unreliable on shared hosts),
#   L0_REQUESTER (default http://127.0.0.1:8082, the local L0 API per host),
#   SOCIAL_CONSUMERIDSUFFIX (optional CSV parallel to SOCIAL_HOSTS overriding
#     the kafka consumerIdSuffix per node; default derives the first DNS label
#     of each SOCIAL_ADVERTISED entry, e.g. socialeu1 from socialeu1.bigtangle.org),
#   READINESS_TIMEOUT_MINUTES (default 3 — -Dbigtangle.readinessTimeoutMinutes),
#   KAFKA_BOOTSTRAP (default the 2 prod mirrors), SERVER_APIKEY, JAVA_OPTS.
set -euo pipefail

SSH_USER="${SSH_USER:-root}"
SSH_OPTS="${SSH_OPTS:--o BatchMode=yes -o ConnectTimeout=10 -i /config/.ssh/oraclevpc.key}"
JUMP_HOST="${JUMP_HOST-}"

SOCIAL_HOSTS="${SOCIAL_HOSTS:-s1001.bigt.ai,s2001.bigt.ai}"
SOCIAL_ADVERTISED="${SOCIAL_ADVERTISED:-socialeu1.bigtangle.org,socialeu2.bigtangle.org}"
SOCIAL_IMAGE="${SOCIAL_IMAGE:-ghcr.io/bigt-ai-platform/l1-social-server}"
# Pin the latest versioned GH container tag (2026-09-05: 0.6.3); override to roll forward.
IMAGE_TAG="${IMAGE_TAG:-0.6.3}"
CONTAINER="${CONTAINER:-l1-social-server}"
SOCIAL_PORT="${SOCIAL_PORT:-8091}"
# Bind loopback only: the public entrypoint is Caddy (TLS). This keeps the
# plaintext HTTP port off the wire entirely.
SOCIAL_BIND="${SOCIAL_BIND:-127.0.0.1}"
# Per-host system Caddy site directory (imported by the main Caddyfile).
CADDY_SITES_DIR="${CADDY_SITES_DIR:-/etc/caddy/Caddyfile.d}"
SERVER_NET="${SERVER_NET:-Mainnet}"
DB_NAME="${DB_NAME:-social}"
DB_PORT="${DB_PORT:-5432}"
DB_USERNAME="${DB_USERNAME:-root}"
DB_PASSWORD="${DB_PASSWORD:-test1234}"
# Per-host postgres pinning (same order as SOCIAL_HOSTS).
# Default matches the live inventory (prod.md §10): s1001 dedicated social-pg,
# s2001 shared l0-pg. s1001 runs 8 pg containers, so auto-detect (first
# postgres container) picks the wrong DB — override explicitly via env only
# when the inventory changes.
PG_CONTAINERS="${PG_CONTAINERS:-social-pg,l0-pg}"
L0_REQUESTER="${L0_REQUESTER:-http://127.0.0.1:8082}"
# Per-node kafka consumer group suffix (optional CSV parallel to SOCIAL_HOSTS).
# Defaults to the first DNS label of the matching SOCIAL_ADVERTISED entry.
SOCIAL_CONSUMERIDSUFFIX="${SOCIAL_CONSUMERIDSUFFIX:-}"
KAFKA_BOOTSTRAP="${KAFKA_BOOTSTRAP:-kafkaeu1.bigtangle.org:9092,kafkaeu2.bigtangle.org:9092}"
JAVA_OPTS="${JAVA_OPTS:--Xmx2g}"
# Container readiness gate (matches the live boot flag -Dbigtangle.readinessTimeoutMinutes=3).
READINESS_TIMEOUT_MINUTES="${READINESS_TIMEOUT_MINUTES:-3}"
# Validator/bridge mode (all default off). POS_VALIDATOR_KEY turns this L1 node
# into a SOCIAL validator; BRIDGE_VAULT_PUBKEY + BRIDGE_L0_URL enable the
# bridge so the node mints L0 vault peg-ins (chainId SOCIAL) to the beneficiary.
# BRIDGE_ISSUANCE_PUBKEY/BRIDGE_ISSUANCE_PRIKEY (dedicated issuance pair, R4)
# sign that wrapped mint — without them finalized peg-ins are skipped.
# POS_VALIDATOR_KEYS (optional CSV parallel to SOCIAL_HOSTS) sets a distinct
# validator seed per host, falling back to POS_VALIDATOR_KEY for all hosts.
POS_VALIDATOR_KEY="${POS_VALIDATOR_KEY:-}"
POS_VALIDATOR_KEYS="${POS_VALIDATOR_KEYS:-}"
BRIDGE_VAULT_PUBKEY="${BRIDGE_VAULT_PUBKEY:-}"
BRIDGE_L0_URL="${BRIDGE_L0_URL:-https://eu1.bigtangle.org}"
BRIDGE_ISSUANCE_PUBKEY="${BRIDGE_ISSUANCE_PUBKEY:-}"
BRIDGE_ISSUANCE_PRIKEY="${BRIDGE_ISSUANCE_PRIKEY:-}"

log() { echo "[prod-social] $*"; }
die() { echo "[prod-social] FAIL: $*" >&2; exit 1; }

IFS=',' read -ra HOSTS <<< "$SOCIAL_HOSTS"
IFS=',' read -ra ADVS <<< "$SOCIAL_ADVERTISED"
[ "${#HOSTS[@]}" -ge 1 ] || die "SOCIAL_HOSTS is empty"
[ "${#HOSTS[@]}" -eq "${#ADVS[@]}" ] || die "SOCIAL_HOSTS (${#HOSTS[@]}) and SOCIAL_ADVERTISED (${#ADVS[@]}) must list the same number of entries"
PGCS=()
if [ -n "$PG_CONTAINERS" ]; then
    IFS=',' read -ra PGCS <<< "$PG_CONTAINERS"
    [ "${#PGCS[@]}" -eq "${#HOSTS[@]}" ] || die "PG_CONTAINERS (${#PGCS[@]}) must match SOCIAL_HOSTS (${#HOSTS[@]})"
fi
PKEYS=()
if [ -n "$POS_VALIDATOR_KEYS" ]; then
    IFS=',' read -ra PKEYS <<< "$POS_VALIDATOR_KEYS"
    [ "${#PKEYS[@]}" -eq "${#HOSTS[@]}" ] || die "POS_VALIDATOR_KEYS (${#PKEYS[@]}) must match SOCIAL_HOSTS (${#HOSTS[@]})"
fi
CSUFFIX=()
if [ -n "$SOCIAL_CONSUMERIDSUFFIX" ]; then
    IFS=',' read -ra CSUFFIX <<< "$SOCIAL_CONSUMERIDSUFFIX"
    [ "${#CSUFFIX[@]}" -eq "${#HOSTS[@]}" ] || die "SOCIAL_CONSUMERIDSUFFIX (${#CSUFFIX[@]}) must match SOCIAL_HOSTS (${#HOSTS[@]})"
fi
# Every node on the shared broker MUST get a distinct kafka consumerIdSuffix,
# else they join ONE consumer group and partitions are served to a single
# member (broadcast starvation → permanent head divergence, no finality).
declare -A SEEN_CSFX=()
for i in "${!HOSTS[@]}"; do
    local_csfx=""
    if [ "${#CSUFFIX[@]}" -gt 0 ]; then
        local_csfx="${CSUFFIX[$i]}"
    else
        local_csfx="${ADVS[$i]%%.*}"
    fi
    if [ -z "$local_csfx" ]; then
        die "cannot derive kafka consumerIdSuffix for ${HOSTS[$i]} — set SOCIAL_CONSUMERIDSUFFIX"
    fi
    if [ -n "${SEEN_CSFX[$local_csfx]:-}" ]; then
        die "duplicate kafka consumerIdSuffix '${local_csfx}' across nodes — every SOCIAL node needs a distinct CONSUMERIDSUFFIX (shared suffix = one kafka consumer group = partition starvation)"
    fi
    SEEN_CSFX[$local_csfx]=1
done

ssh_transport() {
    if [ -n "${JUMP_HOST:-}" ]; then
        echo "ssh $SSH_OPTS -J ${JUMP_HOST} -o StrictHostKeyChecking=accept-new"
    else
        echo "ssh $SSH_OPTS -o StrictHostKeyChecking=accept-new"
    fi
}

remote() { # $1=host  rest=command
    local host="$1"; shift
    # shellcheck disable=SC2086
    $(ssh_transport) "${SSH_USER}@${host}" "$*"
}

pg_container_of() { # $1=host-index → running postgres container name (empty if none)
    local idx="$1"
    local host="${HOSTS[$idx]}"
    if [ "${#PGCS[@]}" -gt 0 ]; then
        local want="${PGCS[$idx]}"
        if remote "$host" "docker ps --format '{{.Names}}' | grep -qx '$want'" 2>/dev/null; then
            printf '%s' "$want"
        else
            log "${host}: pinned postgres ${want} not running"
        fi
        return 0
    fi
    remote "$host" "docker ps --format '{{.Names}} {{.Image}}' | awk '\$2 ~ /postgres/ {print \$1; exit}'" 2>/dev/null || true
}

ensure_db() { # $1=host-index — CREATE DATABASE DB_NAME when absent
    local idx="$1"
    local host="${HOSTS[$idx]}" pgc
    pgc="$(pg_container_of "$idx")"
    [ -n "$pgc" ] || die "${host}: no running postgres container found"
    log "${host}: ensuring database ${DB_NAME} (via ${pgc})"
    remote "$host" "docker exec ${pgc} psql -U '${DB_USERNAME}' -d postgres -tc \"SELECT 1 FROM pg_database WHERE datname='${DB_NAME}'\" | grep -q 1" \
        || remote "$host" "docker exec ${pgc} psql -U '${DB_USERNAME}' -d postgres -c 'CREATE DATABASE ${DB_NAME};'" \
        || die "${host}: cannot create database ${DB_NAME}"
}

ensure_caddy() { # $1=host $2=advertised domain → write site file + reload system Caddy
    local host="$1" adv="$2" site="${2}.caddy" clean
    clean="${adv//./-}"
    if [ -z "$CADDY_SITES_DIR" ]; then
        log "${host}: Caddy step disabled (CADDY_SITES_DIR empty)"
        return 0
    fi
    if ! remote "$host" "command -v caddy >/dev/null 2>&1 && systemctl is-active caddy >/dev/null 2>&1"; then
        log "WARN ${host}: no active system Caddy — leaving ${adv} on plain http://:${SOCIAL_PORT}"
        return 0
    fi
    remote "$host" "cat > ${CADDY_SITES_DIR}/${site} <<'EOF'
${adv} {
    reverse_proxy 127.0.0.1:${SOCIAL_PORT}
    header {
        Strict-Transport-Security \"max-age=31536000; includeSubDomains; preload\"
        X-Content-Type-Options \"nosniff\"
        X-Frame-Options \"DENY\"
    }
    log {
        output file /var/log/caddy/${clean}-access.log
    }
}
http://${adv} {
    redir https://${adv}{uri} permanent
}
EOF" || die "${host}: cannot write ${CADDY_SITES_DIR}/${site}"
    log "${host}: wrote ${CADDY_SITES_DIR}/${site}; reloading Caddy"
    remote "$host" "systemctl reload caddy" || die "${host}: caddy reload failed"
}

start_one() { # $1=host-index
    local idx="$1"
    local host="${HOSTS[$idx]}" adv="${ADVS[$idx]}"
    local pk="" csfx=""
    if [ "${#PKEYS[@]}" -gt 0 ]; then
        pk="${PKEYS[$idx]}"
    else
        pk="${POS_VALIDATOR_KEY:-}"
    fi
    if [ "${#CSUFFIX[@]}" -gt 0 ]; then
        csfx="${CSUFFIX[$idx]}"
    else
        csfx="${adv%%.*}"
    fi
    log "${host} (${adv}): pulling ${SOCIAL_IMAGE}:${IMAGE_TAG}"
    remote "$host" "docker pull ${SOCIAL_IMAGE}:${IMAGE_TAG} >/dev/null" \
        || die "${host}: image pull failed"
    ensure_db "$idx"
    if ! getent hosts "$adv" >/dev/null 2>&1; then
        log "WARN: ${adv} does not resolve locally — create the DNS A record"
    fi
    log "${host} (${adv}): starting ${CONTAINER} bound to ${SOCIAL_BIND}:${SOCIAL_PORT} (requester=${L0_REQUESTER})"
    remote "$host" "docker rm -f ${CONTAINER} >/dev/null 2>&1 || true"
    # Secrets travel as env vars, never CLI args (visible in ps).
    # Validator mode: POS_VALIDATOR_KEY (or the per-host POS_VALIDATOR_KEYS
    # entry) enables PoS duties (dutyEnabled defaults true once a key is set).
    # Bridge mode (BRIDGE_VAULT_PUBKEY) turns this L1 node into the SOCIAL-chain
    # minter that watches L0 vault locks via the anchor L0 URL and mints the
    # wrapped peg-in to the beneficiary.
    local validator_env=""
    if [ -n "$pk" ]; then
        validator_env="-e POS_VALIDATOR_KEY='${pk}'"
        log "${host} (${adv}): validator key configured (pubkey-derived identity)"
    fi
    local bridge_env=""
    local chainl_args=""
    if [ -n "${BRIDGE_VAULT_PUBKEY:-}" ]; then
        bridge_env="-e BRIDGE_ACTIVE=true -e BRIDGE_VAULTPUBKEYHEX='${BRIDGE_VAULT_PUBKEY}' -e ANCHOR_L0URL='${BRIDGE_L0_URL:-https://eu1.bigtangle.org}'"
        # The L1 PegInWatcher only mints when chainlength scheduling is active.
        chainl_args="--service.schedule.chainlength=true"
        log "${host} (${adv}): bridge enabled (vault ${BRIDGE_VAULT_PUBKEY:0:16}… → L0 ${BRIDGE_L0_URL:-https://eu1.bigtangle.org})"
        if [ -n "${BRIDGE_ISSUANCE_PUBKEY:-}" ] && [ -n "${BRIDGE_ISSUANCE_PRIKEY:-}" ]; then
            bridge_env="${bridge_env} -e BRIDGE_ISSUANCEPUBKEYHEX='${BRIDGE_ISSUANCE_PUBKEY}' -e BRIDGE_ISSUANCEPRIKEYHEX='${BRIDGE_ISSUANCE_PRIKEY}'"
            log "${host} (${adv}): issuance keys configured (mint signing enabled)"
        elif [ -n "${BRIDGE_ISSUANCE_PUBKEY:-}" ] || [ -n "${BRIDGE_ISSUANCE_PRIKEY:-}" ]; then
            die "${host}: BRIDGE_ISSUANCE_PUBKEY and BRIDGE_ISSUANCE_PRIKEY must be set together"
        else
            log "WARN ${host} (${adv}): bridge enabled WITHOUT issuance keys — finalized peg-ins will be skipped"
        fi
    fi
    remote "$host" "docker run -d --name ${CONTAINER} --network host --restart unless-stopped" \
        "--init --stop-timeout 30" \
        "-e HEALTHCHECK_PORT=${SOCIAL_PORT}" \
        "-e SERVER_APIKEY='${SERVER_APIKEY:-}'" \
        "-e JAVA_OPTS='${JAVA_OPTS}'" \
        "-e CONSUMERIDSUFFIX='${csfx}'" \
        "${validator_env}" \
        "${bridge_env}" \
        "--entrypoint java ${SOCIAL_IMAGE}:${IMAGE_TAG}" \
        "${JAVA_OPTS} --add-exports java.base/sun.nio.ch=ALL-UNNAMED --add-exports java.base/java.lang=ALL-UNNAMED -Dbigtangle.readinessTimeoutMinutes=${READINESS_TIMEOUT_MINUTES} -jar /app/app.jar" \
        "--server.port=${SOCIAL_PORT}" "--server.address=${SOCIAL_BIND}" "--server.net=${SERVER_NET}" \
        "--server.requester=${L0_REQUESTER}" \
        "--db.hostname=localhost --db.port=${DB_PORT} --db.dbName=${DB_NAME}" \
        "--db.username=${DB_USERNAME} --db.password=${DB_PASSWORD}" \
        "--server.createtable=true" \
        "--kafka.bootstrapServers=${KAFKA_BOOTSTRAP}" \
        "${chainl_args}" \
        ">/dev/null" \
        || die "${host}: container start failed"
    ensure_caddy "$host" "$adv"
}

wait_api() { # $1=host $2=adv — up to ~4 min for first boot (schema creation)
    local host="$1" adv="$2"
    for _ in $(seq 1 80); do
        if remote "$host" "curl -sf -m 3 http://127.0.0.1:${SOCIAL_PORT}/ >/dev/null 2>&1"; then
            log "${host}: API up"
            if [ -n "$CADDY_SITES_DIR" ]; then
                for __ in $(seq 1 20); do
                    if curl -fsS -m 5 "https://${adv}/" >/dev/null 2>&1; then
                        log "${host}: HTTPS up (https://${adv}/)"
                        return 0
                    fi
                    sleep 3
                done
                log "WARN ${host}: API up but https://${adv}/ not answering (Caddy cert still issuing?)"
            fi
            return 0
        fi
        sleep 3
    done
    die "${host}: API not up — docker logs ${CONTAINER}"
}

cmd_up() {
    if [ "$DB_PASSWORD" = "test1234" ]; then
        log "WARN: default DB_PASSWORD in use — override DB_PASSWORD in prod"
    fi
    for i in "${!HOSTS[@]}"; do
        start_one "$i"
    done
    for i in "${!HOSTS[@]}"; do
        wait_api "${HOSTS[$i]}" "${ADVS[$i]}"
    done
    cmd_status
}

cmd_status() {
    local rc=0 i host adv st api https
    for i in "${!HOSTS[@]}"; do
        host="${HOSTS[$i]}" adv="${ADVS[$i]}"
        st="$(remote "$host" "docker inspect -f '{{.State.Status}}' ${CONTAINER} 2>/dev/null" || echo missing)"
        api="down"
        remote "$host" "curl -sf -m 5 http://127.0.0.1:${SOCIAL_PORT}/ >/dev/null 2>&1" && api="up"
        https="-"
        curl -fsS -m 5 "https://${adv}/" >/dev/null 2>&1 && https="up"
        printf '  %-16s %-28s container=%-10s api=%-5s https=%s\n' "$host" "$adv" "$st" "$api" "$https"
        [ "$st" = "running" ] && [ "$api" = "up" ] || rc=1
    done
    return "$rc"
}

cmd_logs() { # [N]
    local n="${1:-50}"
    for host in "${HOSTS[@]}"; do
        echo "===== ${host} ====="
        remote "$host" "docker logs --tail=${n} ${CONTAINER} 2>&1" || true
    done
}

cmd_down() {
    for host in "${HOSTS[@]}"; do
        log "${host}: stopping ${CONTAINER} (database kept)"
        remote "$host" "docker rm -f ${CONTAINER} >/dev/null 2>&1 || true"
    done
}

usage() { sed -n '2,40p' "$0" | sed 's/^# \{0,1\}//'; }

case "${1:-}" in
    up) shift; cmd_up "$@" ;;
    status) shift; cmd_status "$@" ;;
    logs) shift; cmd_logs "$@" ;;
    down) shift; cmd_down "$@" ;;
    *) usage; exit 1 ;;
esac
