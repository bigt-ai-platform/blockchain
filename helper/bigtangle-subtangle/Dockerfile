FROM ubuntu:22.04 AS builder

RUN apt-get update

RUN export DEBIAN_FRONTEND=noninteractive && \
 ln -fs /usr/share/zoneinfo/Europa/London /etc/localtime \
 && apt-get install -y tzdata \
 && dpkg-reconfigure --frontend noninteractive tzdata

RUN apt-get install -y locales ntp ntpdate
RUN  \
 dpkg-reconfigure --frontend noninteractive tzdata locales && \
  locale-gen en_US.UTF-8 && \
  /usr/sbin/update-locale LANG=en_US.UTF-8

RUN apt-get update && apt-get -y install git  openjdk-25-jdk maven
RUN apt-get install -y autoconf libtool make
RUN  git clone https://github.com/bigtangle/secp256k1.git
RUN cd secp256k1/ && git checkout jni && ./autogen.sh \
 && ./configure --enable-jni --enable-experimental --enable-module-schnorr --enable-module-ecdh \
 && make &&  cp .libs/libsecp256k1.so /usr/lib/

COPY . .
RUN  mvn  -DskipTests=true clean install

## runtime
FROM   eclipse-temurin:25-jdk-jammy

ARG BIGTANGLEVERSION
RUN ulimit -n 65536
RUN mkdir -p /app/bigtangle-subtangle/
COPY   helper/bigtangle/ca.pkcs12   /app/bigtangle-subtangle/
COPY --from=builder /bigtangle-subtangle/target/bigtangle-subtangle-*-exec.jar \
     /app/bigtangle-subtangle.jar

COPY --from=builder /usr/lib/libsecp256k1.so  /usr/lib/

ENTRYPOINT ["sh", "-c", "java ${JAVA_OPTS}  --add-exports java.base/sun.nio.ch=ALL-UNNAMED -jar /app/bigtangle-subtangle.jar"]