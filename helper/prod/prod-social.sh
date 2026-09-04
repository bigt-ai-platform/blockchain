#!/usr/bin/env bash
# prod-social.sh — deploy l1-social-server on s1001 + s2001 (bigt.ai).
#
# One container per host (--network host), each with its own SOCIAL-chain
# database on the host's local postgres. The instances sync cross-layer via
# the local L0 requester and stream via the 2 prod Kafka mirrors:
#   kafkaeu1.bigtangle.org, kafkaeu2.bigtangle.org.
#
# Public API per host (DNS A records, port 8091):
#   s1001.bigt.ai  →  socialeu1.bigtangle.org:8091
#   s2001.bigt.ai  →  socialeu2.bigtangle.org:8091
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
#   IMAGE_TAG (default latest versioned GH tag, e.g. 0.6.2 — never `latest`),
#   SOCIAL_PORT (default 8091),
#   SERVER_NET (default Mainnet), DB_NAME (default social — the yml default
#     `payment` is a copy-paste; always override so a future payment server on
#     the same postgres cannot collide), DB_PORT (default 5432),
#   DB_USERNAME DB_PASSWORD (default root/test1234 — override in prod),
#   PG_CONTAINERS (optional comma list parallel to SOCIAL_HOSTS pinning the
#     postgres container per host; default auto-detects the first postgres
#     container, which is unreliable on shared hosts),
#   L0_REQUESTER (default http://127.0.0.1:8081, the local L0 API per host),
#   KAFKA_BOOTSTRAP (default the 2 prod mirrors), SERVER_APIKEY, JAVA_OPTS.
set -euo pipefail

SSH_USER="${SSH_USER:-root}"
SSH_OPTS="${SSH_OPTS:--o BatchMode=yes -o ConnectTimeout=10 -i /config/.ssh/oraclevpc.key}"
JUMP_HOST="${JUMP_HOST-}"

SOCIAL_HOSTS="${SOCIAL_HOSTS:-s1001.bigt.ai,s2001.bigt.ai}"
SOCIAL_ADVERTISED="${SOCIAL_ADVERTISED:-socialeu1.bigtangle.org,socialeu2.bigtangle.org}"
SOCIAL_IMAGE="${SOCIAL_IMAGE:-ghcr.io/bigt-ai-platform/l1-social-server}"
# Pin the latest versioned GH container tag (2026-09-04: 0.6.2); override to roll forward.
IMAGE_TAG="${IMAGE_TAG:-0.6.2}"
CONTAINER="${CONTAINER:-l1-social-server}"
SOCIAL_PORT="${SOCIAL_PORT:-8091}"
SERVER_NET="${SERVER_NET:-Mainnet}"
DB_NAME="${DB_NAME:-social}"
DB_PORT="${DB_PORT:-5432}"
DB_USERNAME="${DB_USERNAME:-root}"
DB_PASSWORD="${DB_PASSWORD:-test1234}"
# Optional per-host postgres pinning (same order as SOCIAL_HOSTS),
# e.g. PG_CONTAINERS="social-pg,l0-pg". Empty = auto-detect.
PG_CONTAINERS="${PG_CONTAINERS:-}"
L0_REQUESTER="${L0_REQUESTER:-http://127.0.0.1:8081}"
KAFKA_BOOTSTRAP="${KAFKA_BOOTSTRAP:-kafkaeu1.bigtangle.org:9092,kafkaeu2.bigtangle.org:9092}"
JAVA_OPTS="${JAVA_OPTS:--Xmx2g}"

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

start_one() { # $1=host-index
    local idx="$1"
    local host="${HOSTS[$idx]}" adv="${ADVS[$idx]}"
    log "${host} (${adv}): pulling ${SOCIAL_IMAGE}:${IMAGE_TAG}"
    remote "$host" "docker pull ${SOCIAL_IMAGE}:${IMAGE_TAG} >/dev/null" \
        || die "${host}: image pull failed"
    ensure_db "$idx"
    if ! getent hosts "$adv" >/dev/null 2>&1; then
        log "WARN: ${adv} does not resolve locally — create the DNS A record"
    fi
    log "${host} (${adv}): starting ${CONTAINER} on :${SOCIAL_PORT} (requester=${L0_REQUESTER})"
    remote "$host" "docker rm -f ${CONTAINER} >/dev/null 2>&1 || true"
    # Secrets travel as env vars, never CLI args (visible in ps).
    remote "$host" "docker run -d --name ${CONTAINER} --network host --restart unless-stopped" \
        "--init --stop-timeout 30" \
        "-e HEALTHCHECK_PORT=${SOCIAL_PORT}" \
        "-e SERVER_APIKEY='${SERVER_APIKEY:-}'" \
        "-e JAVA_OPTS='${JAVA_OPTS}'" \
        "--entrypoint java ${SOCIAL_IMAGE}:${IMAGE_TAG}" \
        "${JAVA_OPTS} --add-exports java.base/sun.nio.ch=ALL-UNNAMED --add-exports java.base/java.lang=ALL-UNNAMED -jar /app/app.jar" \
        "--server.port=${SOCIAL_PORT}" "--server.net=${SERVER_NET}" \
        "--server.requester=${L0_REQUESTER}" \
        "--db.hostname=localhost --db.port=${DB_PORT} --db.dbName=${DB_NAME}" \
        "--db.username=${DB_USERNAME} --db.password=${DB_PASSWORD}" \
        "--server.createtable=true" \
        "--kafka.bootstrapServers=${KAFKA_BOOTSTRAP}" \
        ">/dev/null" \
        || die "${host}: container start failed"
}

wait_api() { # $1=host — up to ~4 min for first boot (schema creation)
    local host="$1"
    for _ in $(seq 1 80); do
        if remote "$host" "curl -sf -m 3 http://127.0.0.1:${SOCIAL_PORT}/ >/dev/null 2>&1"; then
            log "${host}: API up"
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
    for host in "${HOSTS[@]}"; do
        wait_api "$host"
    done
    cmd_status
}

cmd_status() {
    local rc=0 i host adv st api
    for i in "${!HOSTS[@]}"; do
        host="${HOSTS[$i]}" adv="${ADVS[$i]}"
        st="$(remote "$host" "docker inspect -f '{{.State.Status}}' ${CONTAINER} 2>/dev/null" || echo missing)"
        api="down"
        remote "$host" "curl -sf -m 5 http://127.0.0.1:${SOCIAL_PORT}/ >/dev/null 2>&1" && api="up"
        printf '  %-16s %-28s container=%-10s api=%s\n' "$host" "$adv" "$st" "$api"
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

usage() { sed -n '2,34p' "$0" | sed 's/^# \{0,1\}//'; }

case "${1:-}" in
    up) shift; cmd_up "$@" ;;
    status) shift; cmd_status "$@" ;;
    logs) shift; cmd_logs "$@" ;;
    down) shift; cmd_down "$@" ;;
    *) usage; exit 1 ;;
esac
