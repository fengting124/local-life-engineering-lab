# ── LocalLife Server Dockerfile ──
# 多阶段构建：Build Stage 编译，Runtime Stage 只保留 jar，镜像更小

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

# 先只复制 pom 文件，利用 Docker 层缓存（依赖不变时跳过 mvn download）
COPY pom.xml .
COPY local-life-server/pom.xml local-life-server/pom.xml
COPY local-life-copilot/pom.xml local-life-copilot/pom.xml

# 复制源码并编译（跳过测试加速构建）。不使用 dependency:go-offline：
# Maven dependency plugin 会解析 ShardingSphere 已排除的缺失传递依赖，正常 package 不受影响。
#
# 用重试循环包裹 mvn package：ShardingSphere 5.5.0 传递依赖庞大，镜像仓库偶发 502 /
# 网络抖动会让个别构件解析失败，且 Maven 会把「解析失败」缓存进本地仓库、同一次构建内
# 不再重试。加 -U 强制重新拉取这些被缓存的失败构件，最多重试 3 次——成功下载的构件留在
# 同一镜像层的本地仓库里，重试只补拉失败的那几个，代价很小，却能消除单次抖动导致的整体失败。
COPY . .
RUN for i in 1 2 3; do \
        echo "=== mvn package attempt $i ==="; \
        mvn package -pl local-life-server -am -DskipTests -q -U && exit 0; \
        echo "attempt $i failed, retrying in 15s..."; sleep 15; \
    done; \
    echo "mvn package failed after 3 attempts" >&2; exit 1

# ───── Stage 2: Runtime ─────
FROM eclipse-temurin:17-jre-alpine AS runtime

# 安全：不以 root 运行
RUN addgroup -S appgroup && adduser -S appuser -G appgroup
WORKDIR /app

# 从 Build Stage 复制 fat jar
COPY --from=build /workspace/local-life-server/target/*.jar app.jar

# JVM 生产调优
ENV JAVA_OPTS="-XX:+UseContainerSupport \
               -XX:MaxRAMPercentage=75.0 \
               -XX:+UseG1GC \
               -XX:+HeapDumpOnOutOfMemoryError \
               -XX:HeapDumpPath=/tmp/heapdump.hprof \
               -Djava.security.egd=file:/dev/./urandom"

USER appuser
EXPOSE 8080

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
