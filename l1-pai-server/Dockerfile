FROM eclipse-temurin:25-jre-jammy

# Run as an unprivileged user. Container orchestration mounts (config,
# keystore, logs) must be readable/writable by UID 10001.
RUN groupadd -g 10001 bigtangle && useradd -u 10001 -g bigtangle -m -s /bin/bash bigtangle

WORKDIR /app
COPY --chown=bigtangle:bigtangle target/l1-pai-server-0.6.0-exec.jar app.jar

USER bigtangle

EXPOSE 8080

# Liveness: the actuator health endpoint must answer UP. Override the probe
# port via the HEALTHCHECK_PORT env var when the node binds a custom port.
HEALTHCHECK --interval=30s --timeout=5s --start-period=90s --retries=3 \
  CMD ["bash", "-c", "exec 3<>/dev/tcp/127.0.0.1/${HEALTHCHECK_PORT:-${SERVER_PORT:-8080}}; printf 'GET /actuator/health HTTP/1.0\\r\\nHost: localhost\\r\\n\\r\\n' >&3; RESP=$(cat <&3); case \"$RESP\" in *\\\"status\\\":\\\"UP\\\"*) exit 0 ;; *) exit 1 ;; esac"]

ENTRYPOINT ["sh", "-c", "java ${JAVA_OPTS} --add-exports java.base/sun.nio.ch=ALL-UNNAMED --add-exports java.base/java.lang=ALL-UNNAMED -jar app.jar"]
