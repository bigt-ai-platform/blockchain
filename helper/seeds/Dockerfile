

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

RUN apt-get update && apt-get -y install git  openjdk-17-jdk maven
RUN apt-get install -y autoconf libtool make 
 
COPY . .
RUN  mvn  -DskipTests=true clean install



## runtime
FROM   eclipse-temurin:17-jdk-jammy
 
RUN ulimit -n 65536
RUN mkdir -p /app/bigtangle-seeds/
COPY   helper/seeds/ca.pkcs12   /app/bigtangle-seeds/
COPY --from=builder /bigtangle-seeds/target/bigtangle-seeds-*-exec.jar \
     /app/bigtangle-seeds.jar
 
 
ENTRYPOINT ["sh", "-c", "java ${JAVA_OPTS}  --add-exports java.base/sun.nio.ch=ALL-UNNAMED -jar /app/bigtangle-seeds.jar"]