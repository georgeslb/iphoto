# syntax=docker/dockerfile:1
#
# Multi-stage build for iphoto (Spring Boot backend + Vite/React frontend, bundled into one jar -
# see pom.xml's frontend-maven-plugin executions, which build the frontend and copy
# frontend/dist into target/classes/static as part of a normal `mvn package`, no separate step
# needed here).
#
# JDK 25, not 21: pom.xml's maven-compiler-plugin explicitly targets release 25
# (<source>25</source>/<target>25</target>), separate from the <java.version>21</java.version>
# property - this matches the JDK actually used to build/run this project locally (an
# IntelliJ-managed JDK 26 install, which can target release 25 fine), not the older JDK 21
# that happens to be on this machine's system PATH.
#
# Tests are deliberately NOT run as part of this image build: the project's integration tests
# (src/test/java) spin up a real Postgres via Testcontainers, which needs Docker access that a
# `docker build` step doesn't have by default (Docker-in-Docker is its own can of worms) - run
# `mvn test` as a separate CI step instead (see .github/workflows/docker-image.yml), which runs
# directly on the Actions runner and has normal Docker access.

FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /workspace

# Warm the Maven dependency cache in its own layer so a source-only change doesn't
# re-resolve the whole dependency tree (this project pulls in fairly large native-binary
# artifacts - javacv/opencv-platform, ffmpeg-platform, DJL/pytorch - on top of the usual Spring
# Boot set).
COPY pom.xml .
RUN mvn -B -q -Dmaven.test.skip=true dependency:go-offline

# Full source + build. This one `mvn package` invocation also runs the frontend build and the
# DJL face/feature model downloads (see pom.xml's frontend-maven-plugin/maven-antrun-plugin
# executions, generate-resources phase) - both need network access, fine on a CI runner.
COPY . .
RUN mvn -B -q -Dmaven.test.skip=true package

FROM eclipse-temurin:25-jre AS runtime
WORKDIR /app

# javacv's opencv-platform/ffmpeg-platform artifacts bundle their own native .so files, but they
# still commonly dlopen a handful of base system libraries to actually load. This list is
# best-effort (derived from what OpenCV/FFmpeg native builds typically need on a minimal Debian
# base), not verified against a real run of this exact image in this environment - if face
# detection/duplicate-hashing/thumbnail generation fails at runtime with a native
# UnsatisfiedLinkError, start here. curl is only for the HEALTHCHECK below.
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl libgomp1 libsm6 libxext6 libxrender1 \
    && rm -rf /var/lib/apt/lists/*

COPY --from=build /workspace/target/iphoto-*.jar app.jar

# iphoto.storage.base (see application.yml) resolves relative paths for originals/thumbnails/
# temp/chunks underneath it - mount a volume here so photo data outlives the container.
# Spring Boot's relaxed env-var binding maps IPHOTO_STORAGE_BASE -> iphoto.storage.base
# automatically, no application.yml change needed.
ENV IPHOTO_STORAGE_BASE=/data/iphoto
VOLUME ["/data/iphoto"]

# Not set here on purpose - application.yml's spring.datasource.* defaults to
# localhost:5432/postgres/password, which is only reachable if you run with host networking; a
# normal container deployment MUST override these (SPRING_DATASOURCE_URL/_USERNAME/_PASSWORD,
# same relaxed-binding convention) to point at a real, network-reachable Postgres instance.
# Likewise IPHOTO_ADMIN_PASSWORD is worth overriding away from the "admin/admin" default, and
# IMMICH_URL/IMMICH_API_KEY only matter if you're using the Immich migration feature.

EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=40s --retries=5 \
    CMD curl -f http://localhost:8080/ || exit 1

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
