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
// The processor is loaded by the JVM that runs the build, not the one the application targets:
// a consumer building on an older JDK than this library's toolchain must still be able to load it.
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
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
