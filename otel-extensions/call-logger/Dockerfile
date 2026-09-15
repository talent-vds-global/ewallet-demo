# syntax=docker/dockerfile:1
# Build extension call-logger roi chep vao volume dung chung "otel-ext".
# Service trong docker-compose mount volume nay read-only tai /otel/ext.
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app
COPY pom.xml .
COPY src ./src
RUN --mount=type=cache,id=ewallet-m2,target=/root/.m2,sharing=locked \
    mvn -B -q clean package

FROM busybox:1.36
COPY --from=build /app/target/call-logger.jar /dist/call-logger.jar
CMD ["cp", "/dist/call-logger.jar", "/otel-ext/call-logger.jar"]
