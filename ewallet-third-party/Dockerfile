# syntax=docker/dockerfile:1
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app
COPY pom.xml .
COPY src ./src
# Cache ~/.m2 dung chung giua cac lan build va giua 7 service (sharing=locked: moi luc mot
# service ghi, tranh 7 luong tai cung luc tu Maven Central de dut ket noi giua chung).
# Test chay tren may dev bang scripts/run-tests.ps1, image khong can bien dich test hay JaCoCo.
RUN --mount=type=cache,id=ewallet-m2,target=/root/.m2,sharing=locked \
    mvn -B -q clean package -Dmaven.test.skip=true -Djacoco.skip=true

FROM eclipse-temurin:17-jre
WORKDIR /app
ADD https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/download/v2.9.0/opentelemetry-javaagent.jar /otel/opentelemetry-javaagent.jar
COPY --from=build /app/target/*.jar app.jar
ENTRYPOINT ["java", "-jar", "app.jar"]
