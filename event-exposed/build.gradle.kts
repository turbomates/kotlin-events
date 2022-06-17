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
    testImplementation("org.junit.jupiter:junit-jupiter-api:5.8.1")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.8.1")
}

tasks.getByName<Test>("test") {
    useJUnitPlatform()
}
