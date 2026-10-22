# platform-api image (audit 2026-10-22, X-10/X-11): pinned base tags, multi-stage, non-root runtime, healthcheck.
FROM eclipse-temurin:21.0.5_11-jdk-alpine AS builder
WORKDIR /app
COPY gradlew .
COPY gradle gradle
COPY build.gradle.kts .
COPY settings.gradle.kts .
RUN chmod +x gradlew && ./gradlew dependencies --no-daemon
COPY src src
RUN ./gradlew bootJar --no-daemon

FROM eclipse-temurin:21.0.5_11-jre-alpine
# wget (busybox) is used by HEALTHCHECK; /app/logs is the logback-spring.xml file appender target,
# /app/files the default STORAGE_LOCAL_PATH — both writable by the unprivileged user and mounted as volumes.
RUN addgroup -S -g 1001 app && adduser -S -u 1001 -G app -h /app app \
    && mkdir -p /app/logs /app/files && chown -R app:app /app
WORKDIR /app
COPY --from=builder --chown=app:app /app/build/libs/*.jar app.jar
USER app
EXPOSE 8080
# server.servlet.context-path is /api/v1 (application.yml); /actuator/health is permitAll in SecurityConfig.
HEALTHCHECK --interval=30s --timeout=5s --start-period=120s --retries=3 \
  CMD wget -qO- http://127.0.0.1:8080/api/v1/actuator/health | grep -q '"UP"' || exit 1
ENTRYPOINT ["java", "-jar", "app.jar"]
