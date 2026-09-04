#!/usr/bin/env bash
# prod.sh — deploy the Layer-0 (layer0-server) pair on s1001 + s2001, streamed
# over the 2-broker prod Kafka mirror and exposed through the system Caddy.
#
# One L0 node per host (--network host). The nodes exchange blocks through the
# shared Kafka cluster (topics bigtangle-{blocks,transactions,attestations}-L0,
# see helper/prod/kafka/prod-kafka.sh), which is the PRIMARY transport; P2P
# gossip and the peer requester stay as sync/fallback.
#
#   s1001.bigt.ai  85.214.37.95   L0 node 0   API :8082   https://eu1.bigtangle.org/
#   s2001.bigt.ai  85.215.91.140  L0 node 1   API :8082   https://eu2.bigtangle.org/
#
# Caddy terminates TLS (auto Let's Encrypt) and reverse-proxies to each node's
# loopback API. The L0 API also binds 0.0.0.0 on :8082 because the two nodes
# reach each other over the public IPs (requester/gossip); open TCP 8082 + the
# gossip/peer ports to the peer host's IP in the cloud security list.
#
# DB: each host gets its own dedicated postgres 16 container (`l0eu-pg`,
# loopback :5441, volume /data/l0eu-pg, database `layer0`) — kept off the
# shared 5432/5433/5434 namespaces (s1001: social-pg/router-db/bigtai-db;
# s2001: l0-pg).
#
# Usage:
#   prod.sh up        ensure postgres, (re)start L0 nodes, wire Caddy HTTPS,
#                     verify https://eu1/eu2.bigtangle.org
#   prod.sh status    per-host container + API + HTTPS state
#   prod.sh logs [N]  tail node logs (default 50 lines)
#   prod.sh down      stop + remove L0 containers (postgres/data kept)
#
# Env overrides:
#   SSH_USER SSH_OPTS JUMP_HOST,
#   L0_HOSTS (default "s1001.bigt.ai,s2001.bigt.ai"),
#   L0_ADVERTISED (default "eu1.bigtangle.org,eu2.bigtangle.org", same order),
#   L0_IMAGE (default ghcr.io/bigt-ai-platform/layer0-server), IMAGE_TAG
#     (default latest versioned GH tag, e.g. 0.6.2 — never `latest`),
#   CONTAINER (default l0-server), L0_API_PORTS (default "8082,8082"),
#   SERVER_NET (default Mainnet), SERVER_CHAIN (default L0),
#   STORE_DOMAIN (default core), L0_JAVA_OPTS (default -Xmx5028m ...),
#   DB_PORT (default 5441 — loopback on every host), DB_NAME (default layer0),
#   DB_USERNAME DB_PASSWORD (default root/test1234 — override in prod),
#   PG_CONTAINERS (optional per-host pinning like "social-pg,l0-pg"),
#   KAFKA_BOOTSTRAP (default the 2 prod mirrors), RUN_KAFKA (default true),
#   L0_PEER_UDP L0_PEER_TCP L0_GOSSIP (default 30317/30318/9195),
#   POS_DUTY (default true; requires POS_VALIDATOR_KEY for beacon duties),
#   L0_SEEDS (optional "ip:port,ip:port" requester list; default derived from
#     L0_HOSTS by excluding each node's self), SERVER_APIKEY, JAVA_OPTS.
set -euo pipefail

SSH_USER="${SSH_USER:-root}"
SSH_OPTS="${SSH_OPTS:--o BatchMode=yes -o ConnectTimeout=10 -i /config/.ssh/oraclevpc.key}"
JUMP_HOST="${JUMP_HOST-}"

L0_HOSTS="${L0_HOSTS:-s1001.bigt.ai,s2001.bigt.ai}"
L0_ADVERTISED="${L0_ADVERTISED:-eu1.bigtangle.org,eu2.bigtangle.org}"
L0_IMAGE="${L0_IMAGE:-ghcr.io/bigt-ai-platform/layer0-server}"
# Pin the latest versioned GH container tag (2026-09-04: 0.6.2); override to roll forward.
IMAGE_TAG="${IMAGE_TAG:-0.6.2}"
CONTAINER="${CONTAINER:-l0-server}"
# Per-host L0 API listener ports. 8082 is free on both hosts (s1001 keeps 8081
# for reg-europa; 30308 on s1001 is taken by the legacy bigtangle 0.3.5).
L0_API_PORTS="${L0_API_PORTS:-8082,8082}"
SERVER_NET="${SERVER_NET:-Mainnet}"
SERVER_CHAIN="${SERVER_CHAIN:-L0}"
STORE_DOMAIN="${STORE_DOMAIN:-core}"
# Dedicated postgres per host: loopback 5441 (5432/5433/5434 are used by other
# products on s1001). Override DB_PORT/PG_CONTAINERS when reusing existing pg.
DB_PORT="${DB_PORT:-5441}"
DB_NAME="${DB_NAME:-layer0}"
DB_USERNAME="${DB_USERNAME:-root}"
DB_PASSWORD="${DB_PASSWORD:-test1234}"
PG_CONTAINERS="${PG_CONTAINERS:-}"
PG_IMAGE="${PG_IMAGE:-postgres:16}"
KAFKA_BOOTSTRAP="${KAFKA_BOOTSTRAP:-kafkaeu1.bigtangle.org:9092,kafkaeu2.bigtangle.org:9092}"
RUN_KAFKA="${RUN_KAFKA:-true}"
L0_PEER_UDP="${L0_PEER_UDP:-30317}"
L0_PEER_TCP="${L0_PEER_TCP:-30318}"
L0_GOSSIP="${L0_GOSSIP:-9195}"
POS_DUTY="${POS_DUTY:-true}"
CADDY_SITES_DIR="${CADDY_SITES_DIR:-/etc/caddy/Caddyfile.d}"
L0_JAVA_OPTS="${L0_JAVA_OPTS:--Xmx5028m --add-exports java.base/sun.nio.ch=ALL-UNNAMED --add-exports java.base/java.lang=ALL-UNNAMED}"

log() { echo "[prod] $*"; }
die() { echo "[prod] FAIL: $*" >&2; exit 1; }

IFS=',' read -ra HOSTS <<< "$L0_HOSTS"
IFS=',' read -ra ADVS <<< "$L0_ADVERTISED"
IFS=',' read -ra APORTS <<< "$L0_API_PORTS"
[ "${#HOSTS[@]}" -ge 1 ] || die "L0_HOSTS is empty"
[ "${#HOSTS[@]}" -eq "${#ADVS[@]}" ] || die "L0_HOSTS (${#HOSTS[@]}) and L0_ADVERTISED (${#ADVS[@]}) must match"
[ "${#HOSTS[@]}" -eq "${#APORTS[@]}" ] || die "L0_HOSTS (${#HOSTS[@]}) and L0_API_PORTS (${#APORTS[@]}) must match"
PGCS=()
if [ -n "$PG_CONTAINERS" ]; then
    IFS=',' read -ra PGCS <<< "$PG_CONTAINERS"
    [ "${#PGCS[@]}" -eq "${#HOSTS[@]}" ] || die "PG_CONTAINERS (${#PGCS[@]}) must match L0_HOSTS (${#HOSTS[@]})"
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

host_ip() { # $1=hostname → public IP (resolves s1001.bigt.ai -> 85.214.37.95)
    getent hosts "$1" | awk '{print $1; exit}'
}

# Requester / gossip mesh = every peer (excluding self). L0_SEEDS overrides.
# REQUESTER is the full peer list; validator_common excludes SELF at runtime —
# here we derive peers directly so each node points at the other node only.
peer_list() { # $1=my-index → requester strings of the other nodes
    local me="$1" out=""
    if [ -n "${L0_SEEDS:-}" ]; then printf '%s' "$L0_SEEDS"; return 0; fi
    for j in "${!HOSTS[@]}"; do
        [ "$j" = "$me" ] && continue
        local ip
        ip="$(host_ip "${HOSTS[$j]}")"
        [ -n "$ip" ] || die "cannot resolve ${HOSTS[$j]}"
        out="${out}${out:+,}http://${ip}:${APORTS[$j]}"
    done
    printf '%s' "$out"
}

peer_ips() { # $1=my-index → "ip:gossip" of the other nodes (gossip.peers)
    local me="$1" out=""
    for j in "${!HOSTS[@]}"; do
        [ "$j" = "$me" ] && continue
        local ip
        ip="$(host_ip "${HOSTS[$j]}")"
        [ -n "$ip" ] || die "cannot resolve ${HOSTS[$j]}"
        out="${out}${out:+,}${ip}:${L0_GOSSIP}"
    done
    printf '%s' "$out"
}

# ---- Postgres --------------------------------------------------------------
pg_container_of() { # $1=host-index → pg container name
    local idx="$1"
    local host="${HOSTS[$idx]}"
    if [ "${#PGCS[@]}" -gt 0 ]; then
        printf '%s' "${PGCS[$idx]}"
        return 0
    fi
    printf 'l0eu-pg'
}

ensure_pg() { # $1=host-index — create dedicated postgres when not pinned
    local idx="$1"
    local host="${HOSTS[$idx]}" pgc
    pgc="$(pg_container_of "$idx")"
    if remote "$host" "docker ps --format '{{.Names}}' | grep -qx '${pgc}'" 2>/dev/null; then
        log "${host}: postgres ${pgc} already running"
        return 0
    fi
    log "${host}: creating postgres ${pgc} on 127.0.0.1:${DB_PORT} (${DB_NAME})"
    remote "$host" "docker rm -f ${pgc} >/dev/null 2>&1 || true"
    remote "$host" "mkdir -p /data/${pgc} && docker run -d --name ${pgc} --restart unless-stopped" \
        "-e POSTGRES_USER='${DB_USERNAME}' -e POSTGRES_PASSWORD='${DB_PASSWORD}' -e POSTGRES_DB='${DB_NAME}'" \
        "-p 127.0.0.1:${DB_PORT}:5432 -v /data/${pgc}:/var/lib/postgresql/data ${PG_IMAGE} >/dev/null" \
        || die "${host}: cannot start postgres ${pgc}"
    for _ in $(seq 1 20); do
        remote "$host" "docker exec ${pgc} pg_isready -U '${DB_USERNAME}' -h 127.0.0.1 -p 5432 >/dev/null 2>&1" && { log "${host}: postgres ${pgc} ready"; return 0; }
        sleep 2
    done
    die "${host}: postgres ${pgc} not ready"
}

ensure_db() { # $1=host-index — CREATE DATABASE DB_NAME when absent (pinned pg path)
    local idx="$1"
    local host="${HOSTS[$idx]}" pgc
    pgc="$(pg_container_of "$idx")"
    remote "$host" "docker exec ${pgc} psql -U '${DB_USERNAME}' -d postgres -tc \"SELECT 1 FROM pg_database WHERE datname='${DB_NAME}'\" | grep -q 1" \
        || remote "$host" "docker exec ${pgc} psql -U '${DB_USERNAME}' -d postgres -c 'CREATE DATABASE ${DB_NAME};'" \
        || die "${host}: cannot create database ${DB_NAME} in ${pgc}"
}

# ---- Caddy -----------------------------------------------------------------
ensure_caddy() { # $1=host $2=advertised $3=api-port
    local host="$1" adv="$2" port="$3" clean
    clean="${adv//./-}"
    if [ -z "$CADDY_SITES_DIR" ]; then
        log "${host}: Caddy step disabled (CADDY_SITES_DIR empty)"
        return 0
    fi
    if ! remote "$host" "command -v caddy >/dev/null 2>&1 && systemctl is-active caddy >/dev/null 2>&1"; then
        log "WARN ${host}: no active system Caddy — leaving ${adv} on plain http://:${port}"
        return 0
    fi
    remote "$host" "cat > ${CADDY_SITES_DIR}/${adv}.caddy <<'EOF'
${adv} {
    reverse_proxy 127.0.0.1:${port}
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
EOF" || die "${host}: cannot write ${CADDY_SITES_DIR}/${adv}.caddy"
    log "${host}: wrote ${CADDY_SITES_DIR}/${adv}.caddy; reloading Caddy"
    remote "$host" "systemctl reload caddy" || die "${host}: caddy reload failed"
}

# ---- Node ------------------------------------------------------------------
start_one() { # $1=host-index
    local idx="$1"
    local host="${HOSTS[$idx]}" adv="${ADVS[$idx]}" port="${APORTS[$idx]}" pgc
    pgc="$(pg_container_of "$idx")"
    local requester gossip_seeds pos_peers
    requester="$(peer_list "$idx")"
    gossip_seeds="$(peer_ips "$idx")"
    pos_peers="$requester"
    if ! getent hosts "$adv" >/dev/null 2>&1; then
        log "WARN: ${adv} does not resolve locally — create the DNS A record"
    fi
    log "${host} (${adv}): pulling ${L0_IMAGE}:${IMAGE_TAG}"
    remote "$host" "docker pull ${L0_IMAGE}:${IMAGE_TAG} >/dev/null" || die "${host}: image pull failed"
    log "${host} (${adv}): starting ${CONTAINER} on 0.0.0.0:${port} (db=${DB_NAME}@${pgc}:${DB_PORT})"
    remote "$host" "docker rm -f ${CONTAINER} >/dev/null 2>&1 || true"
    # Secrets as container env vars, never CLI args (visible in ps).
    local kafka_args=("--server.runKafkaStream=false")
    if [ "$RUN_KAFKA" = "true" ] && [ -n "$KAFKA_BOOTSTRAP" ]; then
        kafka_args=("--server.runKafkaStream=true" "--kafka.bootstrapServers=${KAFKA_BOOTSTRAP}")
    fi
    remote "$host" "docker run -d --name ${CONTAINER} --network host --restart unless-stopped" \
        "--init --stop-timeout 30" \
        "-e POS_VALIDATOR_KEY='${POS_VALIDATOR_KEY:-}'" \
        "-e SERVER_APIKEY='${SERVER_APIKEY:-}'" \
        "-e HEALTHCHECK_PORT=${port}" \
        "-e JAVA_OPTS='${L0_JAVA_OPTS}'" \
        "--entrypoint java ${L0_IMAGE}:${IMAGE_TAG}" \
        "${L0_JAVA_OPTS} -jar /app/app.jar" \
        "--server.port=${port}" "--server.address=0.0.0.0" \
        "--server.net=${SERVER_NET}" "--server.chain=${SERVER_CHAIN}" \
        "--store.domain=${STORE_DOMAIN}" \
        "--db.hostname=localhost --db.port=${DB_PORT} --db.dbName=${DB_NAME}" \
        "--db.username=${DB_USERNAME} --db.password=${DB_PASSWORD}" \
        "--server.createtable=true" \
        "${kafka_args[@]}" \
        "--server.requester=${requester}" \
        "--service.schedule.chainlength=true --service.schedule.blockbatch=true" \
        "--service.schedule.microbatch=true --service.schedule.initsync=true" \
        "--peer.udpPort=${L0_PEER_UDP} --peer.tcpPort=${L0_PEER_TCP} --gossip.port=${L0_GOSSIP}" \
        "--gossip.peers=${gossip_seeds}" \
        "--pos.dutyEnabled=${POS_DUTY}" \
        "--pos.gossipPeers=${pos_peers}" \
        ">/dev/null" || die "${host}: container start failed"
    ensure_caddy "$host" "$adv" "$port"
}

wait_api() { # $1=host $2=adv $3=port
    local host="$1" adv="$2" port="$3"
    for _ in $(seq 1 80); do
        if remote "$host" "curl -sf -m 3 http://127.0.0.1:${port}/ >/dev/null 2>&1"; then
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
    if [ "${POS_DUTY}" = "true" ] && [ -z "${POS_VALIDATOR_KEY:-}" ]; then
        log "WARN: POS_DUTY=true but POS_VALIDATOR_KEY is unset — node cannot propose/attest"
    fi
    for i in "${!HOSTS[@]}"; do
        ensure_pg "$i"
    done
    for i in "${!HOSTS[@]}"; do
        ensure_db "$i"
    done
    for i in "${!HOSTS[@]}"; do
        start_one "$i"
    done
    for i in "${!HOSTS[@]}"; do
        wait_api "${HOSTS[$i]}" "${ADVS[$i]}" "${APORTS[$i]}"
    done
    cmd_status
}

cmd_status() {
    local rc=0 i host adv port st api https
    for i in "${!HOSTS[@]}"; do
        host="${HOSTS[$i]}" adv="${ADVS[$i]}" port="${APORTS[$i]}"
        st="$(remote "$host" "docker inspect -f '{{.State.Status}}' ${CONTAINER} 2>/dev/null" || echo missing)"
        api="down"
        remote "$host" "curl -sf -m 5 http://127.0.0.1:${port}/ >/dev/null 2>&1" && api="up"
        https="-"
        curl -fsS -m 5 "https://${adv}/" >/dev/null 2>&1 && https="up"
        printf '  %-16s %-26s api:%-5s container=%-9s https=%s\n' "$host" "$adv" "127.0.0.1:$port" "$st" "$https"
        [ "$st" = "running" ] && [ "$api" = "up" ] || rc=1
    done
    return "$rc"
}

cmd_logs() { # [N]
    local n="${1:-50}" host
    for host in "${HOSTS[@]}"; do
        echo "===== ${host} ====="
        remote "$host" "docker logs --tail=${n} ${CONTAINER} 2>&1" || true
    done
}

cmd_down() {
    local host
    for host in "${HOSTS[@]}"; do
        log "${host}: stopping ${CONTAINER} (postgres + data kept)"
        remote "$host" "docker rm -f ${CONTAINER} >/dev/null 2>&1 || true"
    done
}

usage() { sed -n '2,50p' "$0" | sed 's/^# \{0,1\}//'; }

case "${1:-}" in
    up) shift; cmd_up "$@" ;;
    status) shift; cmd_status "$@" ;;
    logs) shift; cmd_logs "$@" ;;
    down) shift; cmd_down "$@" ;;
    *) usage; exit 1 ;;
esac
