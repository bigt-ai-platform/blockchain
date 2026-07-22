


ARG UBUNTU_TAG=22.04
ARG JAVA_RUNTIME=eclipse-temurin:25-jdk-jammy

FROM ubuntu:${UBUNTU_TAG} AS builder

RUN apt-get update \
 && export DEBIAN_FRONTEND=noninteractive \
 && ln -fs /usr/share/zoneinfo/Europe/London /etc/localtime \
 && apt-get install -y tzdata \
 && dpkg-reconfigure --frontend noninteractive tzdata \
 && apt-get install -y locales ntp ntpdate \
 && dpkg-reconfigure --frontend noninteractive tzdata locales \
 && locale-gen en_US.UTF-8 \
 && /usr/sbin/update-locale LANG=en_US.UTF-8

RUN apt-get update \
 && apt-get -y install git openjdk-25-jdk maven autoconf libtool make

ARG BIGTANGLEVERSION=0.5.0
COPY --from=builder \
     /layer0-server/target/layer0-server-${BIGTANGLEVERSION}-exec.jar \
     /app/layer0-server.jar
COPY --from=builder \
     /layer0-mcmc/target/layer0-mcmc-${BIGTANGLEVERSION}-exec.jar \
     /app/layer0-mcmc.jar

ENV APP_MODULE=layer0-server

ENTRYPOINT ["sh", "-c", "java ${JAVA_OPTS} --add-exports java.base/sun.nio.ch=ALL-UNNAMED -jar /app/${APP_MODULE}.jar"]