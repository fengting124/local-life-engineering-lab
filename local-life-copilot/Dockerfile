# ── LocalLife Copilot MCP Server Dockerfile ──

# ───── Stage 1: Build ─────
FROM maven:3.9.9-eclipse-temurin-17-alpine AS build
WORKDIR /workspace
ENV MAVEN_OPTS="-Djava.net.preferIPv4Stack=true \
                -Dsun.net.client.defaultConnectTimeout=30000 \
                -Dsun.net.client.defaultReadTimeout=30000"
ENV JAVA_TOOL_OPTIONS="${MAVEN_OPTS}"

COPY pom.xml .
COPY local-life-copilot/pom.xml local-life-copilot/pom.xml
COPY local-life-server/pom.xml local-life-server/pom.xml

COPY . .
RUN mvn package -pl local-life-copilot -am -DskipTests -q

# ───── Stage 2: Runtime ─────
FROM eclipse-temurin:17-jre-alpine AS runtime

RUN addgroup -S appgroup && adduser -S appuser -G appgroup
WORKDIR /app

COPY --from=build /workspace/local-life-copilot/target/*.jar app.jar

ENV JAVA_OPTS="-XX:+UseContainerSupport \
               -XX:MaxRAMPercentage=75.0 \
               -XX:+UseG1GC \
               -Djava.security.egd=file:/dev/./urandom"

USER appuser
EXPOSE 8081

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
