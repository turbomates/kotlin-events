rootProject.name = "event"

include("event")
include("event-exposed")
include("event-rabbit")

pluginManagement {
    plugins {
        id("org.jetbrains.kotlin.jvm") version "2.0.0"
    }
}
dependencyResolutionManagement {
    versionCatalogs {
        create("deps") {
            version("detekt", "1.23.6")
            version("kotlin", "2.0.0")
            version("test_logger", "3.2.0")
            version("kotlin_serialization_json", "1.7.0")
            version("slf4j", "2.0.13")
            version("exposed", "0.53")
            version("postgresql_jdbc", "42.7.3")
            version("rabbitmq_amqp_client", "5.20.0")
            version("coroutines", "1.8.1")
            version("testcontainers", "1.19.8")

            library("exposed_time", "org.jetbrains.exposed", "exposed-java-time").versionRef("exposed")
            library("exposed_core", "org.jetbrains.exposed", "exposed-core").versionRef("exposed")
            library("exposed_jdbc", "org.jetbrains.exposed", "exposed-jdbc").versionRef("exposed")
            library("exposed_json", "org.jetbrains.exposed", "exposed-json").versionRef("exposed")
            library("slf4j", "org.slf4j", "slf4j-api").versionRef("slf4j")
            library("kotlin_serialization_json", "org.jetbrains.kotlinx", "kotlinx-serialization-json").versionRef("kotlin_serialization_json")
            library("kotlin_serialization", "org.jetbrains.kotlin", "kotlin-serialization").versionRef("kotlin")
            library("postgresql_jdbc", "org.postgresql", "postgresql").versionRef("postgresql_jdbc")
            library("rabbitmq_amqp_client","com.rabbitmq", "amqp-client").versionRef("rabbitmq_amqp_client")
            library("coroutines","org.jetbrains.kotlinx", "kotlinx-coroutines-core").versionRef("coroutines")
            library("detekt_formatting", "io.gitlab.arturbosch.detekt", "detekt-formatting").versionRef("detekt")
            library("kotlin_coroutines_test", "org.jetbrains.kotlinx", "kotlinx-coroutines-test").versionRef("coroutines")
            library("testcontainers_core", "org.testcontainers", "testcontainers").versionRef("testcontainers")
            library("testcontainers_postgres", "org.testcontainers", "postgresql").versionRef("testcontainers")
            library("testcontainers_rabbit", "org.testcontainers", "rabbitmq").versionRef("testcontainers")

            plugin("test_logger", "com.adarshr.test-logger").versionRef("test_logger")
            plugin("detekt", "io.gitlab.arturbosch.detekt").versionRef("detekt")
            plugin("kotlin_serialization", "org.jetbrains.kotlin.plugin.serialization").versionRef("kotlin")

            bundle(
                "exposed",
                listOf(
                    "exposed_time",
                    "exposed_core",
                    "exposed_jdbc",
                    "exposed_json"
                )
            )
        }
    }
}
