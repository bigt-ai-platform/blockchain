ARG BIGTANGLEVERSION=0.6.0

FROM eclipse-temurin:25-jdk-jammy

ARG BIGTANGLEVERSION

COPY layer0-server/target/layer0-server-${BIGTANGLEVERSION}-exec.jar /app/layer0-server.jar
COPY layer0-mcmc/target/layer0-mcmc-${BIGTANGLEVERSION}-exec.jar /app/layer0-mcmc.jar

ENV APP_MODULE=layer0-server

ENTRYPOINT ["sh", "-c", "java ${JAVA_OPTS} --add-exports java.base/sun.nio.ch=ALL-UNNAMED -jar /app/${APP_MODULE}.jar"]
