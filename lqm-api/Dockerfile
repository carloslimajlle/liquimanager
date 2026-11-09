# Build context is the repository root (the API depends on the engine, the client and the collector):
#   docker build -f lqm-api/Dockerfile -t lqm-api .
FROM maven:3.9-eclipse-temurin-26 AS build
WORKDIR /w
COPY pom.xml ./
COPY lqm-policy-engine/pom.xml lqm-policy-engine/
COPY lqm-core-client/pom.xml lqm-core-client/
COPY lqm-collector/pom.xml lqm-collector/
COPY lqm-api/pom.xml lqm-api/
COPY lqm-cli/pom.xml lqm-cli/
COPY lqm-spring-boot-starter/pom.xml lqm-spring-boot-starter/
COPY lqm-worker/pom.xml lqm-worker/
RUN --mount=type=cache,target=/root/.m2 mvn -B -ntp -q -pl lqm-api -am dependency:go-offline || true
COPY lqm-policy-engine lqm-policy-engine
COPY lqm-core-client lqm-core-client
COPY lqm-collector lqm-collector
COPY lqm-api lqm-api
# docs/08 is read by a test and by the seed generator; the jar itself does not need it.
RUN --mount=type=cache,target=/root/.m2 mvn -B -ntp -q -pl lqm-api -am -DskipTests package \
 && cp lqm-api/target/lqm-api-*.jar /app.jar

FROM eclipse-temurin:21-jre
RUN useradd --system --uid 10001 lqm \
 && mkdir -p /var/lib/lqm/blobs \
 && chown -R lqm /var/lib/lqm
USER lqm
COPY --from=build /app.jar /app.jar
ENV TZ=UTC
# Logs and reports (D-09 puts them in object storage; until then, a directory).
ENV LQM_STORAGE_DIR=/var/lib/lqm/blobs
VOLUME /var/lib/lqm
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app.jar"]
