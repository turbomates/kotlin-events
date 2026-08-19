plugins {
    kotlin("jvm") apply true
    alias(deps.plugins.test.logger)
    alias(deps.plugins.detekt)
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
    implementation(deps.ksp.api)
    detektPlugins(deps.detekt.formatting)
    testImplementation(kotlin("test"))
}

tasks.getByName<Test>("test") {
    useJUnitPlatform()
}
