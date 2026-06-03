# ── LocalLife Copilot MCP Server Dockerfile ──

# ───── Stage 1: Build ─────
FROM maven:3.9.9-eclipse-temurin-17-alpine AS build
WORKDIR /workspace

COPY pom.xml .
COPY ../pom.xml ../pom.xml
RUN mvn dependency:go-offline -pl local-life-copilot -am -q

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
