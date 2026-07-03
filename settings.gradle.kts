rootProject.name = "music-streaming-analytics"

include(":ingestion-service", ":stream-processor", ":query-service")

project(":ingestion-service").projectDir = file("services/ingestion-service")
project(":stream-processor").projectDir = file("services/stream-processor")
project(":query-service").projectDir = file("services/query-service")