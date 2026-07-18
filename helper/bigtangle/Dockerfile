


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

RUN git clone https://github.com/bigtangle/secp256k1.git \
 && cd secp256k1 \
 && git checkout jni \
 && ./autogen.sh \
 && ./configure --enable-jni --enable-experimental --enable-module-schnorr --enable-module-ecdh \
 && make \
 && cp .libs/libsecp256k1.so /usr/lib/

COPY . .
RUN mvn -DskipTests=true clean install

## runtime
FROM ${JAVA_RUNTIME}

RUN ulimit -n 65536 \
 && mkdir -p /app

COPY helper/bigtangle/ca.pkcs12 /app/
COPY --from=builder /usr/lib/libsecp256k1.so /usr/lib/

ARG BIGTANGLEVERSION=0.5.0
COPY --from=builder \
     /layer0-server/target/layer0-server-${BIGTANGLEVERSION}-exec.jar \
     /app/layer0-server.jar
COPY --from=builder \
     /layer0-mcmc/target/layer0-mcmc-${BIGTANGLEVERSION}-exec.jar \
     /app/layer0-mcmc.jar

ENV APP_MODULE=layer0-server

ENTRYPOINT ["sh", "-c", "java ${JAVA_OPTS} --add-exports java.base/sun.nio.ch=ALL-UNNAMED -jar /app/${APP_MODULE}.jar"]