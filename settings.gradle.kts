// 阿里云镜像：国内直连 Gradle Plugin Portal / Maven Central 经常 TLS 握手被重置，改走阿里云镜像加速构建
pluginManagement {
    repositories {
        maven("https://maven.aliyun.com/repository/public")
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        gradlePluginPortal()
    }
}

rootProject.name = "music-streaming-analytics"

include(":ingestion-service", ":stream-processor", ":query-service")

project(":ingestion-service").projectDir = file("services/ingestion-service")
project(":stream-processor").projectDir = file("services/stream-processor")
project(":query-service").projectDir = file("services/query-service")