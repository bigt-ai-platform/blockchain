#!/usr/bin/env bash
# kafka-local.sh — shared local-docker Kafka provisioner for ALL test harnesses.
# Sourced (never executed): provides kafka_local_ensure / kafka_local_topics /
# kafka_local_bootstrap_host / kafka_local_bootstrap_docker.
#
# One hermetic KRaft broker (apache/kafka:3.9.0, combined broker+controller)
# per harness, with two listeners so BOTH host processes (test drivers, mvn
# tests) and docker-networked servers reach the same broker:
#   PLAINTEXT localhost:<host-port>   (host tools, -p published)
#   DOCKER    <container>:9094        (containers on KAFKA_NETWORK)
# Topics carry message.max.bytes=32M (a full batch block of PQ-signed txs must
# fit — the 1 MB broker default silently drops publishes and forks the mesh).
#
# Env overrides (all optional):
#   KAFKA_IMAGE            default apache/kafka:3.9.0
#   KAFKA_CONTAINER        default bt4-kafka
#   KAFKA_HOST_PORT        default 9092 (published to host)
#   KAFKA_NETWORK          default "" (attached when set, for container clients)
#   KAFKA_CHAINS           default "L0" (topic suffixes; e.g. "L0 ordermatch")
#   KAFKA_PARTITIONS       default 1
#   KAFKA_RF               default 1 (single test broker)
#   KAFKA_MAX_MESSAGE_BYTES default 33554432
#   KAFKA_FRESH_TOPICS     default 1 (delete + recreate empty, hermetic runs)
#   KAFKA_CONF_DIR         default /tmp/<container>-kafka-conf (generated
#                          server.properties; resolved lazily per container,
#                          regenerated on every ensure)
#
# Usage from a harness:
#   source "$(repo-root)/helper/kafka-local.sh"
#   kafka_local_ensure                           # start broker if port closed
#   kafka_local_topics                           # (re)create chain topics
#   BOOTSTRAP_HOST="$(kafka_local_bootstrap_host)"     # localhost:PORT
#   BOOTSTRAP_DOCKER="$(kafka_local_bootstrap_docker)" # <container>:9094
#
# NOTE on apache/kafka:3.9.0: passing ANY KAFKA_* env var switches its config
# generation to env-only mode (the KRaft template defaults are dropped and the
# broker dies asking for zookeeper.connect), and its CLI only accepts VAR=VALUE
# (never --override). So this script mounts a COMPLETE generated
# server.properties instead of env vars or CLI overrides.
KAFKA_IMAGE="${KAFKA_IMAGE:-apache/kafka:3.9.0}"
KAFKA_CONTAINER="${KAFKA_CONTAINER:-bt4-kafka}"
KAFKA_HOST_PORT="${KAFKA_HOST_PORT:-9092}"
KAFKA_NETWORK="${KAFKA_NETWORK:-}"
KAFKA_CHAINS="${KAFKA_CHAINS:-L0}"
KAFKA_PARTITIONS="${KAFKA_PARTITIONS:-1}"
KAFKA_RF="${KAFKA_RF:-1}"
KAFKA_MAX_MESSAGE_BYTES="${KAFKA_MAX_MESSAGE_BYTES:-33554432}"
KAFKA_FRESH_TOPICS="${KAFKA_FRESH_TOPICS:-1}"
KAFKA_CONF_DIR="${KAFKA_CONF_DIR:-}"

_kafka_log() { echo "[kafka-local] $*"; }

kafka_local_bootstrap_host() { # host processes (drivers, mvn, curl)
    printf 'localhost:%s' "$KAFKA_HOST_PORT"
}

kafka_local_bootstrap_docker() { # containers sharing KAFKA_NETWORK
    printf '%s:9094' "$KAFKA_CONTAINER"
}

kafka_local_write_config() { # [$1=conf-dir] — full KRaft server.properties
    local dir="${1:-${KAFKA_CONF_DIR:-/tmp/${KAFKA_CONTAINER}-kafka-conf}}"
    mkdir -p "$dir"
    # Base template from the image (KRaft combined defaults), minus the three
    # listener lines which are replaced below with the dual-listener setup.
    docker run --rm --entrypoint cat "$KAFKA_IMAGE" /etc/kafka/docker/server.properties 2>/dev/null \
        | grep -v "^#" | grep -v "^$" \
        | grep -v "^listeners=" | grep -v "^advertised.listeners=" \
        | grep -v "^listener.security.protocol.map=" > "${dir}/server.properties"
    cat >> "${dir}/server.properties" <<EOF
listeners=PLAINTEXT://:9092,CONTROLLER://:9093,DOCKER://:9094
advertised.listeners=PLAINTEXT://localhost:${KAFKA_HOST_PORT},DOCKER://${KAFKA_CONTAINER}:9094
listener.security.protocol.map=CONTROLLER:PLAINTEXT,PLAINTEXT:PLAINTEXT,DOCKER:PLAINTEXT,SSL:SSL,SASL_PLAINTEXT:SASL_PLAINTEXT,SASL_SSL:SASL_SSL
message.max.bytes=${KAFKA_MAX_MESSAGE_BYTES}
replica.fetch.max.bytes=${KAFKA_MAX_MESSAGE_BYTES}
auto.create.topics.enable=false
EOF
}

kafka_local_ensure() {
    # Resolve once: empty KAFKA_CONF_DIR falls back per container name.
    local conf_dir="${KAFKA_CONF_DIR:-/tmp/${KAFKA_CONTAINER}-kafka-conf}"
    if (echo > "/dev/tcp/127.0.0.1/${KAFKA_HOST_PORT}") 2>/dev/null; then
        _kafka_log "${KAFKA_CONTAINER} already serving on :${KAFKA_HOST_PORT}"
    else
        _kafka_log "starting ${KAFKA_CONTAINER} (${KAFKA_IMAGE} on :${KAFKA_HOST_PORT}, docker :9094)"
        docker rm -f "${KAFKA_CONTAINER}" >/dev/null 2>&1 || true
        kafka_local_write_config "$conf_dir"
        local net_args=()
        if [ -n "$KAFKA_NETWORK" ]; then
            docker network inspect "$KAFKA_NETWORK" >/dev/null 2>&1 \
                || docker network create "$KAFKA_NETWORK" >/dev/null
            net_args=(--network "$KAFKA_NETWORK")
        fi
        # shellcheck disable=SC2068
        docker run -d --name "${KAFKA_CONTAINER}" --hostname "${KAFKA_CONTAINER}" "${net_args[@]}" \
            -p "${KAFKA_HOST_PORT}:9092" \
            -v "${conf_dir}/server.properties:/mnt/shared/config/server.properties:ro" \
            "$KAFKA_IMAGE" >/dev/null \
            || { echo "[kafka-local] FAIL: broker start failed" >&2; return 1; }
        sleep 8
    fi
    if [ -n "$KAFKA_NETWORK" ]; then
        docker network inspect "$KAFKA_NETWORK" >/dev/null 2>&1 \
            || docker network create "$KAFKA_NETWORK" >/dev/null
        docker network inspect -f '{{range .Containers}}{{.Name}} {{end}}' "$KAFKA_NETWORK" 2>/dev/null \
            | grep -qw "$KAFKA_CONTAINER" \
            || docker network connect "$KAFKA_NETWORK" "$KAFKA_CONTAINER" >/dev/null 2>&1 || true
    fi
    # Readiness: topics tool answers (up to ~2 min on first pull/format).
    # NOTE: in-container admin must use the DOCKER listener (localhost:9094):
    # the PLAINTEXT advertised port is the HOST mapping, unreachable inside.
    # The container hostname is pinned to KAFKA_CONTAINER so the advertised
    # DOCKER://<name>:9094 resolves here and on every attached network.
    # shellcheck disable=SC2046
    for _ in $(seq 1 60); do
        if timeout 30 docker exec "${KAFKA_CONTAINER}" /opt/kafka/bin/kafka-topics.sh \
            --bootstrap-server localhost:9094 --list >/dev/null 2>&1; then
            _kafka_log "broker ready (${KAFKA_CONTAINER})"
            return 0
        fi
        sleep 2
    done
    echo "[kafka-local] FAIL: broker ${KAFKA_CONTAINER} not ready" >&2
    return 1
}

kafka_local_topics() {
    # (Re)create bigtangle-<base>-<chain> for every base x chain. Fresh mode
    # deletes first so each run starts EMPTY (a stale log would replay old
    # blocks into the new mesh); otherwise create-if-absent + enforce configs.
    # Admin goes through the in-container DOCKER listener (see ensure).
    local kt="docker exec ${KAFKA_CONTAINER} /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9094"
    local base chain t existing
    for chain in $KAFKA_CHAINS; do
        for base in bigtangle-blocks bigtangle-transactions bigtangle-attestations; do
            t="${base}-${chain}"
            if [ "${KAFKA_FRESH_TOPICS:-1}" = "1" ]; then
                # shellcheck disable=SC2086
                $kt --delete --topic "$t" >/dev/null 2>&1 || true
                for _ in $(seq 1 30); do
                    # shellcheck disable=SC2086
                    $kt --list 2>/dev/null | grep -qx "$t" || break
                    sleep 1
                done
            fi
            # shellcheck disable=SC2086
            existing="$($kt --list 2>/dev/null | grep -cx "$t" || true)"
            if [ "${existing:-0}" = "0" ]; then
                local created=0 a
                for a in 1 2 3 4 5 6 7 8; do
                    # shellcheck disable=SC2086
                    $kt --create --topic "$t" --partitions "$KAFKA_PARTITIONS" \
                        --replication-factor "$KAFKA_RF" \
                        --config "max.message.bytes=${KAFKA_MAX_MESSAGE_BYTES}" \
                        --config min.insync.replicas=1 >/dev/null 2>&1 \
                        && { created=1; break; }
                    sleep 3
                done
                [ "$created" = 1 ] || { echo "[kafka-local] FAIL: cannot create $t" >&2; return 1; }
            else
                # shellcheck disable=SC2086
                $kt --alter --topic "$t" \
                    --config "max.message.bytes=${KAFKA_MAX_MESSAGE_BYTES}" >/dev/null 2>&1 || true
            fi
        done
    done
    _kafka_log "topics ready: $(for c in $KAFKA_CHAINS; do printf 'bigtangle-blocks-%s bigtangle-transactions-%s bigtangle-attestations-%s ' "$c" "$c" "$c"; done)"
}
