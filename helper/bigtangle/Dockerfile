ARG BIGTANGLEVERSION=0.6.2

FROM eclipse-temurin:25-jre-jammy

ARG BIGTANGLEVERSION

# Run as an unprivileged user. Container orchestration mounts (config,
# keystore, logs) must be readable/writable by UID 10001.
RUN groupadd -g 10001 bigtangle && useradd -u 10001 -g bigtangle -m -s /bin/bash bigtangle

COPY --chown=bigtangle:bigtangle layer0-server/target/layer0-server-${BIGTANGLEVERSION}-exec.jar /app/app.jar

ENV APP_MODULE=layer0-server

USER bigtangle

EXPOSE 8088

# Liveness: the actuator health endpoint must answer UP. Override the probe
# port via the HEALTHCHECK_PORT env var when the node binds a custom port
# (e.g. --server.port=8081 -> -e HEALTHCHECK_PORT=8081).
HEALTHCHECK --interval=30s --timeout=5s --start-period=90s --retries=3 \
  CMD ["bash", "-c", "exec 3<>/dev/tcp/127.0.0.1/${HEALTHCHECK_PORT:-${SERVER_PORT:-8088}}; printf 'GET /actuator/health HTTP/1.0\\r\\nHost: localhost\\r\\n\\r\\n' >&3; RESP=$(cat <&3); case \"$RESP\" in *\\\"status\\\":\\\"UP\\\"*) exit 0 ;; *) exit 1 ;; esac"]

ENTRYPOINT ["sh", "-c", "java ${JAVA_OPTS} --add-exports java.base/sun.nio.ch=ALL-UNNAMED -jar /app/${APP_MODULE}.jar"]
