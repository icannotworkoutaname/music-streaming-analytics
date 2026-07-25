# 构建阶段
FROM eclipse-temurin:21-jdk AS build
WORKDIR /app
COPY . .
RUN ./gradlew :query-service:bootJar --no-daemon

# 运行阶段
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/services/query-service/build/libs/*.jar app.jar
EXPOSE 8082
ENTRYPOINT ["java", "-jar", "app.jar"]