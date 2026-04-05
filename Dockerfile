# ── Stage 1 : Build ───────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jdk-alpine AS builder

WORKDIR /app

# Cache Maven dependencies first (invalidated only when pom.xml changes)
COPY pom.xml .
COPY module-shared/pom.xml          module-shared/pom.xml
COPY module-merchant/pom.xml        module-merchant/pom.xml
COPY module-auth/pom.xml            module-auth/pom.xml
COPY module-operator/pom.xml        module-operator/pom.xml
COPY module-payment/pom.xml         module-payment/pom.xml
COPY module-webhook/pom.xml         module-webhook/pom.xml
COPY module-wallet/pom.xml          module-wallet/pom.xml
COPY module-notification/pom.xml    module-notification/pom.xml
COPY module-app/pom.xml             module-app/pom.xml

RUN apk add --no-cache maven \
 && mvn dependency:go-offline -B -q

# Copy sources and build the fat JAR (skipping tests — run separately)
COPY . .
RUN mvn clean package -DskipTests -B -q

# Extract layers for optimal Docker cache re-use on re-deployments
WORKDIR /app/module-app/target
RUN java -Djarmode=layertools -jar module-app-1.0.0.jar extract --destination extracted

# ── Stage 2 : Runtime ─────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine

# Security: run as non-root
RUN addgroup -S ebithex && adduser -S ebithex -G ebithex

WORKDIR /app

# Copy layers in order of how often they change (least → most)
COPY --from=builder /app/module-app/target/extracted/dependencies          ./
COPY --from=builder /app/module-app/target/extracted/spring-boot-loader    ./
COPY --from=builder /app/module-app/target/extracted/snapshot-dependencies ./
COPY --from=builder /app/module-app/target/extracted/application           ./

# Fix ownership
RUN chown -R ebithex:ebithex /app

USER ebithex

EXPOSE 8080

# Liveness probe via Spring Boot Actuator
HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
  CMD wget -qO- http://localhost:8080/api/actuator/health | grep -q '"status":"UP"' || exit 1

# JVM tuning for containers
ENV JAVA_OPTS="-XX:+UseContainerSupport \
               -XX:MaxRAMPercentage=75.0 \
               -XX:+UseG1GC \
               -XX:+ExitOnOutOfMemoryError \
               -Djava.security.egd=file:/dev/./urandom"

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS org.springframework.boot.loader.launch.JarLauncher"]