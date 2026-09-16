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
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app.jar"]
