#!/usr/bin/env bash
# bt-kafka.sh — start the site-local Kafka broker (KRaft, single-node) on this host.
# Usage: ./bt-kafka.sh [ADVERTISED_IP]   (default: first 10.8.0.x address)
set -euo pipefail
IP="${1:-$(ip -4 addr show | grep -oP '(?<=inet 10\.8\.0\.)[0-9]+' | head -1 | xargs -I{} echo "10.8.0.{}")}"
docker rm -f bt-kafka >/dev/null 2>&1 || true
CLUSTER_ID=$(docker run --rm confluentinc/cp-kafka:7.7.1 kafka-storage random-uuid)
docker run -d --name bt-kafka --network host --restart unless-stopped \
  -e CLUSTER_ID="$CLUSTER_ID" \
  -e KAFKA_NODE_ID=1 \
  -e KAFKA_PROCESS_ROLES=broker,controller \
  -e KAFKA_CONTROLLER_QUORUM_VOTERS="1@${IP}:9093" \
  -e KAFKA_LISTENERS="PLAINTEXT://0.0.0.0:9092,CONTROLLER://0.0.0.0:9093" \
  -e KAFKA_ADVERTISED_LISTENERS="PLAINTEXT://${IP}:9092" \
  -e KAFKA_LISTENER_SECURITY_PROTOCOL_MAP="CONTROLLER:PLAINTEXT,PLAINTEXT:PLAINTEXT" \
  -e KAFKA_CONTROLLER_LISTENER_NAMES=CONTROLLER \
  -e KAFKA_INTER_BROKER_LISTENER_NAME=PLAINTEXT \
  -e KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR=1 \
  -e KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR=1 \
  -e KAFKA_TRANSACTION_STATE_LOG_MIN_ISR=1 \
  confluentinc/cp-kafka:7.7.1 > /dev/null
echo "bt-kafka up, advertised ${IP}:9092"
