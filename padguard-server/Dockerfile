# ---- Build stage: compile inside Gradle image (no local JDK needed) ----
FROM gradle:8.8-jdk17 AS build
WORKDIR /app

# 全部源码复制进来；依赖下载通过 BuildKit 缓存挂载持久化，
# 首次构建下载一次后，后续 `docker compose up --build` 命中缓存，秒级构建。
COPY . .
RUN --mount=type=cache,target=/home/gradle/.gradle \
    gradle bootJar -x test --no-daemon

# ---- Runtime stage ----
FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /app/build/libs/app.jar app.jar
# 镜像级时区兜底：
# 只设 TZ 依赖镜像内已安装 tzdata，缺失时会静默回落到 UTC；因此再用
# -Duser.timezone 直接钉死 JVM 默认时区，不依赖系统时区数据库。
# 覆盖所有依赖 systemDefault() 的消费点：日志时间戳、@Scheduled cron 触发点、
# 以及基于 java.util.Calendar 的按日/按月分桶统计。
ENV TZ=Asia/Shanghai
EXPOSE 8080
ENTRYPOINT ["java", "-Duser.timezone=Asia/Shanghai", "-jar", "/app/app.jar"]
