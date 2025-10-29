
plugins {
    kotlin("jvm") apply true
    alias(deps.plugins.kotlin.serialization)
}

group = "com.turbomates"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    implementation(deps.bundles.opentelemetry)
    implementation(project(":event"))
    testImplementation(kotlin("test"))
    testImplementation("io.opentelemetry:opentelemetry-sdk:1.55.0")
    testImplementation("io.opentelemetry:opentelemetry-sdk-testing:1.55.0")
    testImplementation("io.opentelemetry:opentelemetry-sdk-trace:1.55.0")
    testImplementation("io.opentelemetry:opentelemetry-extension-trace-propagators:1.55.0")
    testImplementation(deps.kotlin.coroutines.test)
}

tasks.getByName<Test>("test") {
    useJUnitPlatform()
}
