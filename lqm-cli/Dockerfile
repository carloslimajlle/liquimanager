# The `lqm` command as an image for CI jobs. Build context is the repository root:
#   docker build -f lqm-cli/Dockerfile -t lqm-cli .
#
# It carries the drivers the jar bundles (SQL Server, PostgreSQL, Oracle, MySQL), so a job can point `--url` at any of them.
# There is no ENTRYPOINT on purpose: a GitLab job runs `script:` lines through a shell in this image, and an entrypoint
# would swallow them. Use `lqm <command>` from the script.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /w
COPY pom.xml ./
COPY lqm-policy-engine/pom.xml lqm-policy-engine/
COPY lqm-core-client/pom.xml lqm-core-client/
COPY lqm-collector/pom.xml lqm-collector/
COPY lqm-api/pom.xml lqm-api/
COPY lqm-cli/pom.xml lqm-cli/
COPY lqm-spring-boot-starter/pom.xml lqm-spring-boot-starter/
COPY lqm-worker/pom.xml lqm-worker/
RUN --mount=type=cache,target=/root/.m2 mvn -B -ntp -q -pl lqm-cli -am dependency:go-offline || true
COPY lqm-policy-engine lqm-policy-engine
COPY lqm-core-client lqm-core-client
COPY lqm-collector lqm-collector
COPY lqm-cli lqm-cli
RUN --mount=type=cache,target=/root/.m2 mvn -B -ntp -q -pl lqm-cli -am -DskipTests package \
 && cp lqm-cli/target/lqm.jar /lqm.jar

FROM eclipse-temurin:25-jre
# git: `lqm runner` clones the changelog at the commit of the original operation.
RUN apt-get update \
 && apt-get install -y --no-install-recommends git ca-certificates \
 && rm -rf /var/lib/apt/lists/* \
 && useradd --system --create-home --home-dir /home/lqm --uid 10001 lqm \
 && mkdir -p /opt/lqm \
 && printf '#!/bin/sh\nexec java -jar /opt/lqm/lqm.jar "$@"\n' > /usr/local/bin/lqm \
 && chmod +x /usr/local/bin/lqm
COPY --from=build /lqm.jar /opt/lqm/lqm.jar
USER lqm
ENV TZ=UTC
WORKDIR /home/lqm
CMD ["lqm", "--help"]
