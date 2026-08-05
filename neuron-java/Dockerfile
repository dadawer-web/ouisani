# ╔══════════════════════════════════════════════════════════════╗
# ║  AIOS — Cloud Native Dockerfile (Multi-stage Build)        ║
# ║  Universal AI Operating System                              ║
# ╚══════════════════════════════════════════════════════════════╝

# ── Stage 1: Builder ──────────────────────────────────────────
FROM maven:3.9-eclipse-temurin-21 AS builder

WORKDIR /build

# Copy pom.xml first for dependency caching
COPY pom.xml .
RUN mvn dependency:go-offline -B

# Copy source and build
COPY src ./src
RUN mvn clean package -DskipTests -B

# ── Stage 2: Runtime ─────────────────────────────────────────
FROM eclipse-temurin:21-jre-jammy

LABEL maintainer="AIOS Team"
LABEL description="AIOS — Universal AI Operating System Kernel"
LABEL version="0.1.0"

# Create app directory
WORKDIR /opt/aios

# Copy fat jar from builder
COPY --from=builder /build/target/aios.jar /opt/aios/aios-java.jar

# Copy web dashboard resources
COPY --from=builder /build/src/main/resources/web /opt/aios/web

# Copy Python SDK
COPY ../aios-sdk-python/aios.py /opt/aios/sdk/aios.py

# Expose Javalin Dashboard & Gateway port
EXPOSE 8080

# Environment variables (override at runtime)
ENV AIOS_PORT=8080
ENV JAVA_OPTS=""

# Health check
HEALTHCHECK --interval=30s --timeout=5s --start-period=15s --retries=3 \
    CMD curl -f http://localhost:8080/ || exit 1

# Launch AIOS Kernel
ENTRYPOINT ["sh", "-c", "java ${JAVA_OPTS} -cp /opt/aios/aios-java.jar com.ouisani.aios.user.cli.AiosShell"]
