#!/usr/bin/env bash
# prod-kafka.sh — 2-broker KRaft Kafka mirror cluster for prod (primary transport).
#
# One combined broker+controller per host; every chain topic is replicated on
# both brokers, so each broker mirrors all data. Validators consume the full
# bootstrap list; P2P gossip stays a hard fallback only
# (see BlockSaveService.broadcastBytes: gossip fires only when Kafka is off or
# the publish fails).
#
#   s1001.bigt.ai  (node 1)  advertised kafkaeu1.bigtangle.org:9092
#   s2001.bigt.ai  (node 2)  advertised kafkaeu2.bigtangle.org:9092
#
# Usage:
#   prod-kafka.sh up          generate/reuse CLUSTER_ID, start brokers, create topics
#   prod-kafka.sh topics      (re)create + verify chain topics (RF=2) and topic configs
#   prod-kafka.sh status      broker state + topic/URP overview per host
#   prod-kafka.sh bootstrap   print the client bootstrap string for
#                             KAFKA_BOOTSTRAP / BOOT_STRAP_SERVERS
#   prod-kafka.sh down [--wipe]  stop brokers (data kept unless --wipe)
#
# Env overrides:
#   SSH_USER SSH_OPTS JUMP_HOST, KAFKA_HOSTS, KAFKA_ADVERTISED, KAFKA_IMAGE,
#   KAFKA_CONTAINER, KAFKA_DATA_DIR, KAFKA_CHAIN, KAFKA_PARTITIONS,
#   KAFKA_REPLICATION_FACTOR, KAFKA_MAX_MESSAGE_BYTES, KAFKA_RETENTION_MS,
#   CLUSTER_ID (else generated once and stored next to this script).
#
# Prerequisites per host: docker, open TCP 9092 (clients) + 9093 (controllers),
# DNS A records: kafkaeu1/kafkaeu2.bigtangle.org -> host public IPs.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
SSH_USER="${SSH_USER:-root}"
SSH_OPTS="${SSH_OPTS:--o BatchMode=yes -o ConnectTimeout=10 -i /config/.ssh/oraclevpc.key}"
JUMP_HOST="${JUMP_HOST-}"

KAFKA_HOSTS="${KAFKA_HOSTS:-s1001.bigt.ai,s2001.bigt.ai}"
KAFKA_ADVERTISED="${KAFKA_ADVERTISED:-kafkaeu1.bigtangle.org,kafkaeu2.bigtangle.org}"
KAFKA_IMAGE="${KAFKA_IMAGE:-confluentinc/cp-kafka:7.7.1}"
KAFKA_CONTAINER="${KAFKA_CONTAINER:-bt-kafka}"
KAFKA_DATA_DIR="${KAFKA_DATA_DIR:-/data/kafka}"
KAFKA_CHAIN="${KAFKA_CHAIN:-L0}"
KAFKA_PARTITIONS="${KAFKA_PARTITIONS:-1}"
KAFKA_REPLICATION_FACTOR="${KAFKA_REPLICATION_FACTOR:-2}"
# Must cover a full batch block of PQ-signed txs (~6 KB each); mirrors
# KafkaMessageProducer.KAFKA_MAX_MESSAGE_BYTES (32 MB) client-side.
KAFKA_MAX_MESSAGE_BYTES="${KAFKA_MAX_MESSAGE_BYTES:-33554432}"
# Mirror keeps all chain data (-1 = infinite); override to bound disk, e.g. 2592000000 (30d).
KAFKA_RETENTION_MS="${KAFKA_RETENTION_MS:- -1}"
KAFKA_RETENTION_MS="${KAFKA_RETENTION_MS// /}"
CLUSTER_FILE="${SCRIPT_DIR}/.cluster_id"

log() { echo "[prod-kafka] $*"; }
die() { echo "[prod-kafka] FAIL: $*" >&2; exit 1; }

IFS=',' read -ra HOSTS <<< "$KAFKA_HOSTS"
IFS=',' read -ra ADVS <<< "$KAFKA_ADVERTISED"
[ "${#HOSTS[@]}" -eq 2 ] || die "KAFKA_HOSTS must list 2 hosts (got ${#HOSTS[@]})"
[ "${#ADVS[@]}" -eq 2 ] || die "KAFKA_ADVERTISED must list 2 names (got ${#ADVS[@]})"

TOPICS=(
    "bigtangle-blocks-${KAFKA_CHAIN}"
    "bigtangle-transactions-${KAFKA_CHAIN}"
    "bigtangle-attestations-${KAFKA_CHAIN}"
)

ssh_transport() {
    if [ -n "${JUMP_HOST:-}" ]; then
        echo "ssh $SSH_OPTS -J ${JUMP_HOST} -o StrictHostKeyChecking=accept-new"
    else
        echo "ssh $SSH_OPTS -o StrictHostKeyChecking=accept-new"
    fi
}

remote() { # $1=host-index  rest=command
    local i="$1"; shift
    # shellcheck disable=SC2086
    $(ssh_transport) "${SSH_USER}@${HOSTS[$i]}" "$*"
}

cluster_id() {
    if [ -n "${CLUSTER_ID:-}" ]; then printf '%s' "$CLUSTER_ID"; return 0; fi
    if [ -f "$CLUSTER_FILE" ]; then cat "$CLUSTER_FILE"; return 0; fi
    local id=""
    id="$(docker run --rm "$KAFKA_IMAGE" kafka-storage random-uuid 2>/dev/null | tr -d ' \n' || true)"
    if [ -z "$id" ]; then
        id="$(python3 -c "import base64,os;print(base64.urlsafe_b64encode(os.urandom(16)).decode().rstrip('='))")"
    fi
    [ -n "$id" ] || die "could not generate CLUSTER_ID"
    printf '%s' "$id" > "$CLUSTER_FILE"
    chmod 600 "$CLUSTER_FILE"
    printf '%s' "$id"
}

quorum_voters() { # 1@adv1:9093,2@adv2:9093
    printf '1@%s:9093,2@%s:9093' "${ADVS[0]}" "${ADVS[1]}"
}

cmd_up() {
    local cid voters
    cid="$(cluster_id)"
    voters="$(quorum_voters)"
    log "cluster ${cid} voters ${voters}"
    for i in 0 1; do
        local adv="${ADVS[$i]}"
        if ! getent hosts "$adv" >/dev/null 2>&1; then
            log "WARN: ${adv} does not resolve locally — create the DNS A record"
        fi
        log "starting broker $((i + 1))/2 on ${HOSTS[$i]} (advertised ${adv}:9092)"
        remote "$i" "docker rm -f ${KAFKA_CONTAINER} >/dev/null 2>&1 || true"
        # cp-kafka runs as appuser (1000); host dir must be writable or preflight fails.
        remote "$i" "mkdir -p ${KAFKA_DATA_DIR} && chown -R 1000:1000 ${KAFKA_DATA_DIR} && chmod 755 ${KAFKA_DATA_DIR}"
        remote "$i" "mkdir -p ${KAFKA_DATA_DIR} && docker run -d --name ${KAFKA_CONTAINER} --network host --restart unless-stopped" \
            "-v ${KAFKA_DATA_DIR}:/var/lib/kafka/data" \
            "-e CLUSTER_ID=\"$cid\"" \
            "-e KAFKA_NODE_ID=$((i + 1))" \
            "-e KAFKA_PROCESS_ROLES=broker,controller" \
            "-e KAFKA_CONTROLLER_QUORUM_VOTERS=\"$voters\"" \
            "-e KAFKA_LISTENERS=\"PLAINTEXT://0.0.0.0:9092,CONTROLLER://0.0.0.0:9093\"" \
            "-e KAFKA_ADVERTISED_LISTENERS=\"PLAINTEXT://${adv}:9092\"" \
            "-e KAFKA_LISTENER_SECURITY_PROTOCOL_MAP=\"CONTROLLER:PLAINTEXT,PLAINTEXT:PLAINTEXT\"" \
            "-e KAFKA_CONTROLLER_LISTENER_NAMES=CONTROLLER" \
            "-e KAFKA_INTER_BROKER_LISTENER_NAME=PLAINTEXT" \
            "-e KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR=2" \
            "-e KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR=2" \
            "-e KAFKA_TRANSACTION_STATE_LOG_MIN_ISR=1" \
            "-e KAFKA_MESSAGE_MAX_BYTES=${KAFKA_MAX_MESSAGE_BYTES}" \
            "-e KAFKA_REPLICA_FETCH_MAX_BYTES=${KAFKA_MAX_MESSAGE_BYTES}" \
            "-e KAFKA_AUTO_CREATE_TOPICS_ENABLE=false" \
            "$KAFKA_IMAGE >/dev/null" \
            || die "broker start failed on ${HOSTS[$i]}"
    done
    log "waiting for quorum (30s)"
    sleep 30
    cmd_topics
    log "bootstrap: $(cmd_bootstrap)"
}

cmd_topics() {
    # Run topic admin against the first reachable broker.
    local h=""
    for i in 0 1; do
        if remote "$i" "docker exec ${KAFKA_CONTAINER} kafka-topics --bootstrap-server localhost:9092 --list >/dev/null 2>&1"; then
            h="$i"
            break
        fi
    done
    [ -n "$h" ] || die "no reachable broker (run: $0 up)"
    log "admin via ${HOSTS[$h]}"
    local existing
    existing="$(remote "$h" "docker exec ${KAFKA_CONTAINER} kafka-topics --bootstrap-server localhost:9092 --list" 2>/dev/null || true)"
    for t in "${TOPICS[@]}"; do
        if printf '%s\n' "$existing" | grep -qx "$t"; then
            log "topic ${t} exists — enforcing configs"
            remote "$h" "docker exec ${KAFKA_CONTAINER} kafka-topics --bootstrap-server localhost:9092 --alter --topic $t" \
                "--config max.message.bytes=${KAFKA_MAX_MESSAGE_BYTES}" \
                "--config min.insync.replicas=1" \
                "--config retention.ms=${KAFKA_RETENTION_MS} >/dev/null" \
                || die "alter failed for ${t}"
        else
            log "creating topic ${t} (partitions=${KAFKA_PARTITIONS} RF=${KAFKA_REPLICATION_FACTOR})"
            remote "$h" "docker exec ${KAFKA_CONTAINER} kafka-topics --bootstrap-server localhost:9092 --create --topic $t" \
                "--partitions ${KAFKA_PARTITIONS} --replication-factor ${KAFKA_REPLICATION_FACTOR}" \
                "--config max.message.bytes=${KAFKA_MAX_MESSAGE_BYTES}" \
                "--config min.insync.replicas=1" \
                "--config retention.ms=${KAFKA_RETENTION_MS} >/dev/null" \
                || die "create failed for ${t}"
        fi
    done
    remote "$h" "docker exec ${KAFKA_CONTAINER} kafka-topics --bootstrap-server localhost:9092 --describe" 2>/dev/null \
        | grep -E '^(Topic|.*Leader)' || true
}

cmd_status() {
    local rc=0
    for i in 0 1; do
        echo "--- ${HOSTS[$i]} (${ADVS[$i]}:9092) ---"
        remote "$i" "docker inspect -f '{{.Name}} {{.State.Status}} (restart={{.HostConfig.RestartPolicy.Name}})' ${KAFKA_CONTAINER} 2>/dev/null" \
            || { echo "  broker container missing"; rc=1; continue; }
        remote "$i" "docker exec ${KAFKA_CONTAINER} kafka-topics --bootstrap-server localhost:9092 --list 2>/dev/null | sort | tr '\n' ' '; echo" \
            || { echo "  broker unreachable"; rc=1; continue; }
        local urp
        urp="$(remote "$i" "docker exec ${KAFKA_CONTAINER} kafka-topics --bootstrap-server localhost:9092 --describe --under-replicated-partitions 2>/dev/null" || true)"
        if [ -n "$urp" ]; then echo "  UNDER-REPLICATED:"; echo "$urp"; rc=1; else echo "  replication OK (no URP)"; fi
    done
    return "$rc"
}

cmd_bootstrap() {
    printf '%s:9092,%s:9092' "${ADVS[0]}" "${ADVS[1]}"
}

cmd_down() { # [--wipe]
    local wipe=false
    [ "${1:-}" = "--wipe" ] && wipe=true
    for i in 0 1; do
        log "stopping broker on ${HOSTS[$i]}"
        remote "$i" "docker rm -f ${KAFKA_CONTAINER} >/dev/null 2>&1 || true"
        if [ "$wipe" = true ]; then
            log "wiping ${KAFKA_DATA_DIR} on ${HOSTS[$i]}"
            remote "$i" "rm -rf ${KAFKA_DATA_DIR}/[^l]* ${KAFKA_DATA_DIR}/meta.properties 2>/dev/null || true"
        fi
    done
}

usage() { sed -n '2,30p' "$0" | sed 's/^# \{0,1\}//'; }

case "${1:-}" in
    up) shift; cmd_up "$@" ;;
    topics) shift; cmd_topics "$@" ;;
    status) shift; cmd_status "$@" ;;
    bootstrap) shift; cmd_bootstrap "$@" ;;
    down) shift; cmd_down "$@" ;;
    *) usage; exit 1 ;;
esac
