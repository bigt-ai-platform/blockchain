FROM maven:3-eclipse-temurin-25 AS build
WORKDIR /build
COPY pom.xml pom.xml
COPY pom.xml pom.xml
COPY bigtangle-core/pom.xml bigtangle-core/pom.xml
COPY bigtangle-servercore/pom.xml bigtangle-servercore/pom.xml
COPY bigtangle-bridge/pom.xml bigtangle-bridge/pom.xml
COPY l1-contract-mcmc/pom.xml l1-contract-mcmc/pom.xml
COPY l1-contract-server/pom.xml l1-contract-server/pom.xml
COPY l1-nft-mcmc/pom.xml l1-nft-mcmc/pom.xml
COPY l1-nft-server/pom.xml l1-nft-server/pom.xml
COPY l1-order-mcmc/pom.xml l1-order-mcmc/pom.xml
COPY l1-order-server/pom.xml l1-order-server/pom.xml
COPY l1-pai-mcmc/pom.xml l1-pai-mcmc/pom.xml
COPY l1-pai-server/pom.xml l1-pai-server/pom.xml
COPY l1-payment-mcmc/pom.xml l1-payment-mcmc/pom.xml
COPY l1-payment-server/pom.xml l1-payment-server/pom.xml
COPY layer0-mcmc/pom.xml layer0-mcmc/pom.xml
COPY layer0-server/pom.xml layer0-server/pom.xml

RUN mkdir -p /build/$(dirname l1-payment-server/target) && mvn dependency:go-offline -q -pl l1-payment-server -am 2>/dev/null || true
COPY . .
RUN mvn package -Dmaven.test.skip=true -q -pl l1-payment-server -am

FROM eclipse-temurin:25-jre-alpine
WORKDIR /app
COPY --from=build /build/l1-payment-server/target/*-exec.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "--add-exports", "java.base/sun.nio.ch=ALL-UNNAMED", "--add-exports", "java.base/java.lang=ALL-UNNAMED", "-jar", "app.jar"]
