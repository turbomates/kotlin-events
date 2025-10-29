plugins {
    kotlin("jvm")
}

dependencies {
    implementation(project(":event"))
    implementation(platform("io.opentelemetry:opentelemetry-bom:1.14.0"))
    implementation("io.opentelemetry:opentelemetry-api")
    implementation("io.opentelemetry:opentelemetry-context")
}
