# ── LocalLife Copilot MCP Server Dockerfile ──

# ───── Stage 1: Build ─────
FROM maven:3.9.9-eclipse-temurin-17-alpine AS build
WORKDIR /workspace
ENV MAVEN_OPTS="-Djava.net.preferIPv4Stack=true \
                -Dsun.net.client.defaultConnectTimeout=30000 \
                -Dsun.net.client.defaultReadTimeout=30000 \
                -Daether.connector.http.retryHandler.count=5 \
                -Daether.connector.http.retryHandler.requestSentEnabled=true"
ENV JAVA_TOOL_OPTIONS="${MAVEN_OPTS}"

# 把 Maven Central 的下载改走阿里云镜像（见 infra/maven/settings.xml 注释）。
# 放到默认用户 settings 路径 /root/.m2/settings.xml，Maven 自动读取，无需 -s 参数。
COPY infra/maven/settings.xml /root/.m2/settings.xml

COPY pom.xml .
COPY local-life-copilot/pom.xml local-life-copilot/pom.xml
COPY local-life-server/pom.xml local-life-server/pom.xml

# 用重试循环包裹 mvn package：镜像仓库偶发 502 / 网络抖动会让个别构件解析失败，且 Maven
# 会把「解析失败」缓存进本地仓库、同一次构建内不再重试。加 -U 强制重新拉取被缓存的失败构件，
# 最多重试 3 次；成功下载的构件留在同一镜像层，重试只补拉失败的那几个，消除单次抖动导致的失败。
COPY . .
RUN for i in 1 2 3; do \
        echo "=== mvn package attempt $i ==="; \
        mvn package -pl local-life-copilot -am -DskipTests -q -U && exit 0; \
        echo "attempt $i failed, retrying in 15s..."; sleep 15; \
    done; \
    echo "mvn package failed after 3 attempts" >&2; exit 1

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
