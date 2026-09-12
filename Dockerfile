# ---------------------------------------------------------------------------
# Stage 1: build with Maven
# ---------------------------------------------------------------------------
FROM maven:3.9-eclipse-temurin-21 AS build

WORKDIR /src

# Cache dependencies first
COPY pom.xml ./
RUN mvn -B -q dependency:go-offline

# Then copy sources and build
COPY src ./src
RUN mvn -B -q clean package -DskipTests

# ---------------------------------------------------------------------------
# Stage 2: build a minimal runtime image with jlink
# ---------------------------------------------------------------------------
FROM eclipse-temurin:21-jdk-alpine AS jlink

WORKDIR /work
COPY --from=build /src/target/s3forge.jar /work/s3forge.jar

# Analyze the jar and produce a runtime with only the required modules.
RUN jdeps \
      --ignore-missing-deps \
      --multi-release 21 \
      --print-module-deps \
      --class-path /work/s3forge.jar \
      /work/s3forge.jar > /work/modules.txt \
 && jlink \
      --add-modules "$(cat /work/modules.txt),jdk.httpserver,jdk.crypto.ec" \
      --strip-debug \
      --no-man-pages \
      --no-header-files \
      --compress=zip-6 \
      --output /work/runtime

# ---------------------------------------------------------------------------
# Stage 3: minimal runtime
# ---------------------------------------------------------------------------
FROM alpine:3.20

RUN addgroup -S s3forge && adduser -S s3forge -G s3forge

WORKDIR /app
COPY --from=jlink /work/runtime /app/runtime
COPY --from=build /src/target/s3forge.jar /app/s3forge.jar

# Default data directory (mount a volume here to persist).
RUN mkdir -p /data && chown s3forge:s3forge /data

USER s3forge
EXPOSE 8001

ENV S3FORGE_PORT=8001 \
    S3FORGE_DATA_DIR=/data

ENTRYPOINT ["/app/runtime/bin/java", "-jar", "/app/s3forge.jar"]
CMD ["--port", "8001", "--file-system", "/data"]
