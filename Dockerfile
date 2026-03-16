# ─────────────────────────────────────────────────────────────────────────────
# Multi-stage Dockerfile for flight-plan-backend
# Stage 1: extract layered JAR (faster rebuilds via Docker layer cache)
# Stage 2: minimal runtime image (non-root, Alpine JRE)
# ─────────────────────────────────────────────────────────────────────────────

# ── Stage 1: Layer extractor ──────────────────────────────────────────────────
FROM eclipse-temurin:17-jre-alpine AS extractor
WORKDIR /app
# The fat JAR is built by CI before docker build runs
COPY target/flight-plan-backend-*.jar app.jar
RUN java -Djarmode=tools -jar app.jar extract --layers --launcher

# ── Stage 2: Runtime ──────────────────────────────────────────────────────────
FROM eclipse-temurin:17-jre-alpine AS runtime

# IM8 S3: run as non-root user
RUN addgroup -S appgroup && adduser -S appuser -G appgroup
RUN apk upgrade --no-cache
USER appuser

WORKDIR /app

# Copy Spring Boot layers in cache-friendly order (least → most volatile)
COPY --from=extractor /app/dependencies/          ./
COPY --from=extractor /app/spring-boot-loader/    ./
COPY --from=extractor /app/snapshot-dependencies/ ./
#COPY --from=extractor /app/application/           ./

# ✅ Fat JAR — Trivy sees all BOOT-INF/lib/ individually (90+ JARs)
COPY --from=extractor /app/app.jar ./app.jar

# Expose app port (mapped in docker-compose, ECS task definition, and k8s)
EXPOSE 8080

# Health check — ECS will also perform ALB target group health checks
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
  CMD wget -qO- http://localhost:8080/api/health || exit 1

# JVM tuning for containers:
#   UseContainerSupport   → respect cgroup memory limits (default in JDK 17)
#   MaxRAMPercentage=75   → leave 25% headroom for OS/non-heap
#   ExitOnOutOfMemoryError → fail-fast so ECS/k8s can restart the pod/task
ENV JAVA_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError \
  -XX:InitialRAMPercentage=50.0 -Djava.security.egd=file:/dev/./urandom \
  -Dserver.error.include-message=never -Duser.timezone=Asia/Singapore"

# ✅ -jar flag — works with ALL Spring Boot versions, no JarLauncher class issues
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
