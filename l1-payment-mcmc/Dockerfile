FROM eclipse-temurin:25-jre-alpine
WORKDIR /app
COPY target/l1-payment-mcmc-0.6.0.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "--add-exports", "java.base/sun.nio.ch=ALL-UNNAMED", "--add-exports", "java.base/java.lang=ALL-UNNAMED", "-jar", "app.jar"]
