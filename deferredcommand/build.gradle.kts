plugins {
    kotlin("jvm") apply true
    alias(deps.plugins.test.logger)
    alias(deps.plugins.detekt)
    alias(deps.plugins.kotlin.serialization)
}

group = "com.turbomates"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}
detekt {
    toolVersion = deps.versions.detekt.get()
    autoCorrect = false
    parallel = true
    config.setFrom(file("../detekt.yml"))
}
dependencies {
    implementation(deps.kotlin.serialization.json)
    implementation(deps.slf4j)
    detektPlugins(deps.detekt.formatting)
    testImplementation(deps.coroutines)
    testImplementation(kotlin("test"))
}

tasks.getByName<Test>("test") {
    useJUnitPlatform()
}
