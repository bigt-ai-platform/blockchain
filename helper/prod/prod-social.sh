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
#   IMAGE_TAG (default: latest numeric GH container tag, auto-detected e.g. 0.6.3;
#     override to pin — never use `latest`),
#   SOCIAL_PORT (default 8091), SOCIAL_BIND (default 127.0.0.1 — the app is
#   only reachable through Caddy; set 0.0.0.0 only when no Caddy is present),
#   CADDY_SITES_DIR (default /etc/caddy/Caddyfile.d — where per-domain site
#   files are dropped; empty disables the Caddy step),
#   SERVER_NET (default Mainnet), DB_NAME (default social — the yml default
#     `payment` is a copy-paste; always override so a future payment server on
#     the same postgres cannot collide), DB_PORT (default 5432),
#   DB_USERNAME DB_PASSWORD (default root/test1234 — override in prod),
#   PG_CONTAINERS (comma list parallel to SOCIAL_HOSTS pinning the postgres
#     container per host; default "social-pg,l0-pg" — s1001 has 8 pg containers
#     so auto-detect picks the wrong DB),
#   L0_REQUESTER (default http://127.0.0.1:8082, the local L0 API per host —
#     L0 listens on 8082 per helper/prod/prod.sh; 8081 is reg-europa on s1001),
#   CONSUMER_SUFFIXES (optional comma list parallel to SOCIAL_HOSTS overriding
#     the Kafka Streams consumer suffix per host; default derives from
#     SOCIAL_ADVERTISED, e.g. socialeu1/socialeu2 — the suffix MUST differ per
#     host because both nodes bind 8091 and a shared suffix puts both nodes in
#     one consumer group, so the single partition is served to only ONE of them
#     and the other starves: actuator DOWN, chain head stuck),
#   BRIDGE_VAULT_PUBKEY (vault pubkey; enables peg-in minting),
#   BRIDGE_ISSUANCE_PUBKEY/BRIDGE_ISSUANCE_PRIKEY (L1 wrapped-mint signer pair —
#     required with BRIDGE_VAULT_PUBKEY, else the watcher observes but never mints),
#   BRIDGE_L0_URL (default https://eu1.bigtangle.org, the anchor L0),
#   READINESS_TIMEOUT_MINUTES (default 3 — bounds the peer-finalized readiness
#     wait, whose target is cross-chain (L0) for L1 nodes; see below),
#   KAFKA_BOOTSTRAP (default the 2 prod mirrors), SERVER_APIKEY, JAVA_OPTS.
set -euo pipefail

SSH_USER="${SSH_USER:-root}"
SSH_OPTS="${SSH_OPTS:--o BatchMode=yes -o ConnectTimeout=10 -i /config/.ssh/oraclevpc.key}"
JUMP_HOST="${JUMP_HOST-}"

SOCIAL_HOSTS="${SOCIAL_HOSTS:-s1001.bigt.ai,s2001.bigt.ai}"
SOCIAL_ADVERTISED="${SOCIAL_ADVERTISED:-socialeu1.bigtangle.org,socialeu2.bigtangle.org}"
SOCIAL_IMAGE="${SOCIAL_IMAGE:-ghcr.io/bigt-ai-platform/l1-social-server}"
# Latest numeric GH container tag for SOCIAL_IMAGE (never `latest`).
# Uses the gh CLI when available; falls back to the last known version.
latest_image_tag() {
    local out=""
    if command -v gh >/dev/null 2>&1; then
        out="$(gh api "/orgs/bigt-ai-platform/packages/container/l1-social-server/versions?per_page=50" 2>/dev/null | python3 -c '
import sys, json, re
try:
    d = json.load(sys.stdin)
except Exception:
    sys.exit(0)
tags = []
for v in d:
    for t in v.get("metadata", {}).get("container", {}).get("tags") or []:
        if re.fullmatch(r"[0-9]+(\.[0-9]+)*", t):
            tags.append(t)
if tags:
    print(sorted(tags, key=lambda s: [int(x) for x in s.split(".")])[-1])
' 2>/dev/null || true)"
    fi
    if [ -z "$out" ]; then out="0.6.3"; fi
    printf '%s' "$out"
}
# Latest versioned tag at runtime; IMAGE_TAG env override pins a specific one.
IMAGE_TAG="${IMAGE_TAG:-$(latest_image_tag)}"
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
# postgres container) picks the wrong DB — always pin explicitly via env to
# override, never rely on auto-detect.
PG_CONTAINERS="${PG_CONTAINERS:-social-pg,l0-pg}"
# Local L0 API per host (prod.sh listens on 8082; 8081 is reg-europa on s1001).
L0_REQUESTER="${L0_REQUESTER:-http://127.0.0.1:8082}"
KAFKA_BOOTSTRAP="${KAFKA_BOOTSTRAP:-kafkaeu1.bigtangle.org:9092,kafkaeu2.bigtangle.org:9092}"
JAVA_OPTS="${JAVA_OPTS:--Xmx2g}"
# Bound on the peer-finalized readiness wait (AbstractScheduleInitService).
# The wait compares the LOCAL confirmed length against the REQUESTER's
# finalized length — but an L1 node's requester is the L0 chain (cross-layer
# by design), so the target is L0-finalized while local is SOCIAL-confirmed:
# unreachable until SOCIAL itself confirms that many blocks, which needs
# duties, which are gated on readiness. Every L1 restart therefore stalls
# duties/bridge polling for the FULL timeout (default 30 min). 3 min keeps a
# bounded catch-up window without the deadlock. (Proper fix: same-chain
# readiness target; L0 is unaffected — its requesters are same-chain.)
READINESS_TIMEOUT_MINUTES="${READINESS_TIMEOUT_MINUTES:-3}"
# Validator/bridge mode (all default off). POS_VALIDATOR_KEY turns this L1 node
# into a SOCIAL validator; BRIDGE_VAULT_PUBKEY + issuance keys + BRIDGE_L0_URL
# enable the bridge so the node mints L0 vault peg-ins (chainId SOCIAL) to the
# beneficiary. The issuance pair is the L1 chain's dedicated wrapped-mint signer
# (layers.md §5, helper/fulltest/remote.sh L1_BRIDGE_ARGS) — never the vault key.
# INVARIANT: POS_VALIDATOR_KEY must be a key with a stake deposit on the SOCIAL
# chain (same per-host split as the L0 nodes). A key with no deposit abstains
# from every slot ("deposit not active yet") and the chain never confirms —
# verify with: getValidators shows the key, and no abstain lines in the logs.
POS_VALIDATOR_KEY="${POS_VALIDATOR_KEY:-}"
BRIDGE_VAULT_PUBKEY="${BRIDGE_VAULT_PUBKEY:-}"
BRIDGE_ISSUANCE_PUBKEY="${BRIDGE_ISSUANCE_PUBKEY:-}"
BRIDGE_ISSUANCE_PRIKEY="${BRIDGE_ISSUANCE_PRIKEY:-}"
BRIDGE_L0_URL="${BRIDGE_L0_URL:-https://eu1.bigtangle.org}"

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
CSUFS=()
if [ -n "${CONSUMER_SUFFIXES:-}" ]; then
    IFS=',' read -ra CSUFS <<< "$CONSUMER_SUFFIXES"
    [ "${#CSUFS[@]}" -eq "${#HOSTS[@]}" ] || die "CONSUMER_SUFFIXES (${#CSUFS[@]}) must match SOCIAL_HOSTS (${#HOSTS[@]})"
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
    log "${host} (${adv}): pulling ${SOCIAL_IMAGE}:${IMAGE_TAG}"
    remote "$host" "docker pull ${SOCIAL_IMAGE}:${IMAGE_TAG} >/dev/null" \
        || die "${host}: image pull failed"
    ensure_db "$idx"
    if ! getent hosts "$adv" >/dev/null 2>&1; then
        log "WARN: ${adv} does not resolve locally — create the DNS A record"
    fi
    log "${host} (${adv}): starting ${CONTAINER} bound to ${SOCIAL_BIND}:${SOCIAL_PORT} (requester=${L0_REQUESTER})"
    remote "$host" "docker rm -f ${CONTAINER} >/dev/null 2>&1 || true"
    # Kafka consumer identity must be unique per node: the Streams
    # application.id is <handler>_<suffix>-node<port> and both hosts bind the
    # same SOCIAL_PORT, so a shared suffix merges both nodes into one consumer
    # group and a single-partition topic is served to only ONE of them.
    local csuf=""
    if [ "${#CSUFS[@]}" -gt 0 ]; then
        csuf="${CSUFS[$idx]}"
    else
        csuf="${adv%%.*}"
    fi
    [ -n "$csuf" ] || die "empty Kafka consumer suffix for ${host} (set CONSUMER_SUFFIXES)"
    # Secrets travel as env vars, never CLI args (visible in ps).
    # Validator mode: POS_VALIDATOR_KEY enables PoS duties (dutyEnabled defaults
    # true once a key is set). Bridge mode (BRIDGE_VAULT_PUBKEY) turns this L1
    # node into the SOCIAL-chain minter that watches L0 vault locks via the
    # anchor L0 URL and mints the wrapped peg-in to the beneficiary.
    local validator_env=""
    if [ -n "${POS_VALIDATOR_KEY:-}" ]; then
        validator_env="-e POS_VALIDATOR_KEY='${POS_VALIDATOR_KEY}'"
        log "${host} (${adv}): validator key configured (pubkey-derived identity)"
    fi
    local bridge_env=""
    local chainl_args=""
    if [ -n "${BRIDGE_VAULT_PUBKEY:-}" ]; then
        bridge_env="-e BRIDGE_ACTIVE=true -e BRIDGE_VAULTPUBKEYHEX='${BRIDGE_VAULT_PUBKEY}' -e ANCHOR_L0URL='${BRIDGE_L0_URL:-https://eu1.bigtangle.org}'"
        if [ -n "${BRIDGE_ISSUANCE_PUBKEY:-}" ] && [ -n "${BRIDGE_ISSUANCE_PRIKEY:-}" ]; then
            bridge_env="${bridge_env} -e BRIDGE_ISSUANCEPUBKEYHEX='${BRIDGE_ISSUANCE_PUBKEY}' -e BRIDGE_ISSUANCEPRIKEYHEX='${BRIDGE_ISSUANCE_PRIKEY}'"
        else
            log "WARN ${host} (${adv}): bridge vault set but issuance pair missing — PegInWatcher will observe vault locks but never mint (set BRIDGE_ISSUANCE_PUBKEY + BRIDGE_ISSUANCE_PRIKEY)"
        fi
        # The L1 PegInWatcher only mints when chainlength scheduling is active.
        chainl_args="--service.schedule.chainlength=true"
        log "${host} (${adv}): bridge enabled (vault ${BRIDGE_VAULT_PUBKEY:0:16}… → L0 ${BRIDGE_L0_URL:-https://eu1.bigtangle.org})"
    fi
    remote "$host" "docker run -d --name ${CONTAINER} --network host --restart unless-stopped" \
        "--init --stop-timeout 30" \
        "-e HEALTHCHECK_PORT=${SOCIAL_PORT}" \
        "-e CONSUMERIDSUFFIX='${csuf}'" \
        "-e SERVER_APIKEY='${SERVER_APIKEY:-}'" \
        "-e JAVA_OPTS='${JAVA_OPTS}'" \
        "${validator_env}" \
        "${bridge_env}" \
        "--entrypoint java ${SOCIAL_IMAGE}:${IMAGE_TAG}" \
        "${JAVA_OPTS} -Dbigtangle.readinessTimeoutMinutes=${READINESS_TIMEOUT_MINUTES:-3} --add-exports java.base/sun.nio.ch=ALL-UNNAMED --add-exports java.base/java.lang=ALL-UNNAMED -jar /app/app.jar" \
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

usage() { sed -n '2,52p' "$0" | sed 's/^# \{0,1\}//'; }

case "${1:-}" in
    up) shift; cmd_up "$@" ;;
    status) shift; cmd_status "$@" ;;
    logs) shift; cmd_logs "$@" ;;
    down) shift; cmd_down "$@" ;;
    *) usage; exit 1 ;;
esac
