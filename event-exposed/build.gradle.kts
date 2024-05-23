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
    implementation(deps.kotlin.serialization.json)
    implementation(deps.slf4j)
    implementation(deps.bundles.exposed)
    implementation(deps.postgresql.jdbc)
    api(project(":event"))
    testImplementation(kotlin("test"))
    testImplementation(deps.testcontainers.core)
    testImplementation(deps.testcontainers.postgres)
    testImplementation(deps.kotlin.coroutines.test)
}

tasks.getByName<Test>("test") {
    useJUnitPlatform()
}
