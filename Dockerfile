# quick-paste server image: PWA static build + Micronaut fat JAR (SPEC §7, NFR-8).

# --- Stage 1: PWA ---
FROM node:24-alpine AS pwa
WORKDIR /pwa
COPY pwa/package.json pwa/package-lock.json ./
RUN npm ci
COPY pwa/ ./
RUN npm run build

# --- Stage 2: server fat JAR (PWA bundled as static resources) ---
FROM eclipse-temurin:17-jdk AS build
WORKDIR /src
COPY server/ ./
COPY --from=pwa /pwa/dist/ src/main/resources/static/
RUN ./gradlew shadowJar --no-daemon

# --- Stage 3: runtime ---
FROM eclipse-temurin:17-jre
RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*
RUN useradd --system --create-home quickpaste \
    && mkdir -p /data/blobs && chown -R quickpaste:quickpaste /data
USER quickpaste
WORKDIR /app
COPY --from=build /src/build/libs/*-all.jar app.jar
ENV QP_STORAGE_DIR=/data/blobs
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=3 \
    CMD curl -fsS http://localhost:8080/health || exit 1
ENTRYPOINT ["java", "-jar", "app.jar"]
