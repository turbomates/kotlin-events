plugins {
    kotlin("jvm") apply true
    alias(deps.plugins.kotlin.serialization)
}

group = "com.turbomates"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
    kotlinOptions {
        freeCompilerArgs = listOf(
            "-Xopt-in=kotlin.time.ExperimentalTime",
            "-Xlambdas=indy",
            "-Xcontext-receivers"
        )
    }
}

dependencies {
    implementation(deps.kotlin.serialization.json)
    implementation(deps.rabbitmq.amqp.client)
    implementation(deps.coroutines)
    api(project(":event"))
    testImplementation(deps.testcontainers.core)
    testImplementation(deps.testcontainers.rabbit)
    testImplementation(kotlin("test"))
}

tasks.getByName<Test>("test") {
    useJUnitPlatform()
}
