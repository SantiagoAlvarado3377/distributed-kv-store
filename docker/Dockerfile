# ---- Build stage ----
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /workspace

# Copy POMs first to cache dependency downloads
COPY pom.xml .
COPY server/pom.xml server/
COPY client/pom.xml client/

# Download dependencies (cached unless POMs change)
RUN mvn dependency:go-offline -pl server,client --no-transfer-progress

# Copy source and build
COPY server/src server/src
COPY client/src client/src
RUN mvn package -pl server -am --no-transfer-progress -DskipTests

# ---- Runtime stage ----
FROM eclipse-temurin:17-jre-jammy
WORKDIR /app

COPY --from=build /workspace/server/target/server-*.jar app.jar

# Default data directory
VOLUME /data

# Environment variables with sensible defaults
ENV NODE_ID=node-1 \
    NODE_PORT=8081 \
    PRIMARY_HOST=node-1 \
    PEER_NODES="" \
    REPLICATION_FACTOR=3 \
    DATA_DIR=/data

EXPOSE ${NODE_PORT}

ENTRYPOINT ["java", "-jar", "app.jar"]
