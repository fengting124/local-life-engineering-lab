# ── LocalLife Server Dockerfile ──
# 多阶段构建：Build Stage 编译，Runtime Stage 只保留 jar，镜像更小

# ───── Stage 1: Build ─────
FROM maven:3.9.9-eclipse-temurin-17-alpine AS build
WORKDIR /workspace

# 先只复制 pom 文件，利用 Docker 层缓存（依赖不变时跳过 mvn download）
COPY pom.xml .
COPY local-life-server/pom.xml local-life-server/pom.xml
COPY local-life-copilot/pom.xml local-life-copilot/pom.xml

# 下载所有依赖（父 pom + 子模块）
RUN mvn dependency:go-offline -pl local-life-server -am -q

# 复制源码并编译（跳过测试加速构建）
COPY . .
RUN mvn package -pl local-life-server -am -DskipTests -q

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
