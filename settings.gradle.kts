rootProject.name = "event"

include("event")
include("event-exposed")
include("event-rabbit")

pluginManagement {
    plugins {
        id("org.jetbrains.kotlin.jvm") version "1.7.0"
    }
}
enableFeaturePreview("VERSION_CATALOGS")
dependencyResolutionManagement {
    versionCatalogs {
        create("deps") {
            version("detekt", "1.19.0")
            version("kotlin", "1.7.0")
            version("nexus", "1.1.0")
            version("test_logger", "3.0.0")
            version("kotlin_serialization_json", "1.3.1")
            version("slf4j", "1.7.36")
            version("exposed", "0.38.2")
            version("postgresql_jdbc", "42.4.0")
            version("rabbitmq_amqp_client", "5.15.0")
            version("coroutines", "1.6.2")

            library("exposed_time", "org.jetbrains.exposed", "exposed-java-time").versionRef("exposed")
            library("exposed_core", "org.jetbrains.exposed", "exposed-core").versionRef("exposed")
            library("exposed_jdbc", "org.jetbrains.exposed", "exposed-jdbc").versionRef("exposed")
            library("slf4j", "org.slf4j", "slf4j-api").versionRef("slf4j")
            library("kotlin_serialization_json", "org.jetbrains.kotlinx", "kotlinx-serialization-json").versionRef("kotlin_serialization_json")
            library("kotlin_serialization", "org.jetbrains.kotlin", "kotlin-serialization").versionRef("kotlin")
            library("postgresql_jdbc", "org.postgresql", "postgresql").versionRef("postgresql_jdbc")
            library("rabbitmq_amqp_client","com.rabbitmq", "amqp-client").versionRef("rabbitmq_amqp_client")
            library("coroutines","org.jetbrains.kotlinx", "kotlinx-coroutines-core").versionRef("coroutines")

            plugin("test_logger", "com.adarshr.test-logger").versionRef("test_logger")
            plugin("detekt", "io.gitlab.arturbosch.detekt").versionRef("detekt")
            plugin("nexus", "io.github.gradle-nexus.publish-plugin").versionRef("nexus")
            plugin("kotlin_serialization", "org.jetbrains.kotlin.plugin.serialization").versionRef("kotlin")
            bundle(
                "exposed",
                listOf(
                    "exposed_time",
                    "exposed_core",
                    "exposed_jdbc"
                )
            )
        }
    }
}
