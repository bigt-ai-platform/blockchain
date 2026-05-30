FROM ubuntu:22.04

RUN apt-get update

RUN export DEBIAN_FRONTEND=noninteractive && \
    ln -fs /usr/share/zoneinfo/Europe/London /etc/localtime \
    && apt-get install -y tzdata \
    && dpkg-reconfigure --frontend noninteractive tzdata

RUN apt-get install -y locales ntp ntpdate && \
    dpkg-reconfigure --frontend noninteractive tzdata locales && \
    locale-gen en_US.UTF-8 && \
    /usr/sbin/update-locale LANG=en_US.UTF-8

RUN apt-get update && apt-get -y install git openjdk-17-jdk maven
RUN apt-get install -y autoconf libtool make

RUN git clone https://github.com/bigtangle/secp256k1.git && \
    cd secp256k1/ && git checkout jni && ./autogen.sh \
    && ./configure --enable-jni --enable-experimental --enable-module-schnorr --enable-module-ecdh \
    && make && cp .libs/libsecp256k1.so /usr/lib/

WORKDIR /build
COPY . .

# Build full project, skip tests, then pre-cache test-runtime Maven plugins
RUN mvn -DskipTests=true -Dfile.encoding=UTF-8 clean install -q && \
    mvn -Dfile.encoding=UTF-8 dependency:get -Dartifact=org.apache.maven.surefire:surefire-junit-platform:3.2.3 -q

ENV LANG=en_US.UTF-8 MAVEN_OPTS=-Dfile.encoding=UTF-8

# TEST_CLASSES: comma-separated list of test class simple names to run,
# e.g. "ContractTest,DirectExchangeTest"
ENV TEST_CLASSES=""

ENTRYPOINT ["bash", "-c", \
  "mvn test -pl bigtangle-mcmc \
     -Dtest=${TEST_CLASSES} \
     -DSERVER_NET=Test \
     -Dfile.encoding=UTF-8 \
     -DDB_HOSTNAME=${DB_HOSTNAME:-localhost} \
     -DDB_PORT=${DB_PORT:-5432} \
     -DDB_NAME=${DB_NAME:-info} \
     -DDB_USERNAME=${DB_USERNAME:-root} \
     -DDB_PASSWORD=${DB_PASSWORD:-test1234} \
     -DMINIO_URL=${MINIO_URL:-http://localhost:9000} \
     -DMINIO_ACCESS_KEY=${MINIO_ACCESS_KEY:-minioadmin} \
     -DMINIO_SECRET_KEY=${MINIO_SECRET_KEY:-minioadminpassword} \
     -Dfailsafe.rerunFailingTestsCount=0 \
     2>&1 | tee /test-output/test-${TEST_CLASSES}.log; \
   exit ${PIPESTATUS[0]}"]
