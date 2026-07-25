# 构建阶段
FROM eclipse-temurin:21-jdk AS build
WORKDIR /app
COPY . .
RUN ./gradlew :stream-processor:bootJar --no-daemon

# 运行阶段
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/services/stream-processor/build/libs/*.jar app.jar
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "app.jar"]