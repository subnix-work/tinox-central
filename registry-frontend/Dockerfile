# Self-contained multi-stage build (unlike the backend's Dockerfile, no
# host pre-build step needed -- Maven/Java is a stable, containerizable
# toolchain, so this can run standalone: `docker build .` / `docker
# compose build` alone is enough).

FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# Cache dependency resolution in its own layer before copying sources.
COPY pom.xml .
RUN mvn -B -q dependency:go-offline || true

COPY src ./src
RUN mvn -B -q -DskipTests package

# ---------------------------------------------------------------------

FROM registry.access.redhat.com/ubi8/openjdk-21:1.23
ENV LANGUAGE='en_US:en'

COPY --from=build --chown=185 /build/target/quarkus-app/lib/ /deployments/lib/
COPY --from=build --chown=185 /build/target/quarkus-app/*.jar /deployments/
COPY --from=build --chown=185 /build/target/quarkus-app/app/ /deployments/app/
COPY --from=build --chown=185 /build/target/quarkus-app/quarkus/ /deployments/quarkus/

EXPOSE 8082
USER 185
ENV JAVA_OPTS_APPEND="-Dquarkus.http.host=0.0.0.0 -Djava.util.logging.manager=org.jboss.logmanager.LogManager"
ENV JAVA_APP_JAR="/deployments/quarkus-run.jar"

ENTRYPOINT ["/opt/jboss/container/java/run/run-java.sh"]
