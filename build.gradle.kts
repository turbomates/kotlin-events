import java.time.Duration

plugins {
    java
    kotlin("jvm") version "2.4.10"
    alias(deps.plugins.nexus.release)
    id("signing") apply true
    id("maven-publish") apply true
    alias(deps.plugins.detekt)
}
//allprojects {
//    apply(from = rootProject.file("buildScripts/gradle/checkstyle.gradle.kts"))
//
//    if (this.name != "exposed-tests" && this.name != "exposed-bom" && this != rootProject) {
//        apply(from = rootProject.file("buildScripts/gradle/publishing.gradle.kts"))
//    }
//}

//val reportMerge by tasks.registering(ReportMergeTask::class) {
//    output.set(rootProject.buildDir.resolve("reports/detekt/events.xml"))
//}

kotlin {
    jvmToolchain(25)
}

allprojects {
    // the compiler embedded in detekt 1.23 does not know jvm targets above 22,
    // and the target only matters for type resolution, not for the analyzed sources
    tasks.withType(io.gitlab.arturbosch.detekt.Detekt::class).configureEach {
        jvmTarget = "21"
    }
    // detekt 1.23 runs inside the gradle daemon and its embedded compiler throws on a
    // java.version of 25, so `build` on the project toolchain would always fail. detekt
    // is run on its own, on a jdk of 24, by the reviewdog workflow — keep it out of `check`
    plugins.withId("io.gitlab.arturbosch.detekt") {
        tasks.named("check") {
            setDependsOn(dependsOn.filterNot { it is TaskProvider<*> && it.name == "detekt" })
        }
    }
}


group = "com.turbomates"
version = System.getenv("RELEASE_VERSION") ?: "0.1.0"
subprojects {
    apply(plugin = "maven-publish")
    apply(plugin = "java")
    apply(plugin = "signing")
    java {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(25))
        }
        withJavadocJar()
        withSourcesJar()
    }
    publishing {
        publications {
            create<MavenPublication>("mavenJava") {
                groupId = "com.turbomates"
                artifactId = project.name
                version = System.getenv("RELEASE_VERSION") ?: "0.1.0"
                from(components["java"])
            }
            withType<MavenPublication> {
                pom {
                    packaging = "jar"
                    name.set(project.name)
                    description = "Kotlin events library"
                    url = "https://github.com/turbomates/kotlin-events"
                    licenses {
                        license {
                            name.set("MIT license")
                            url.set("https://opensource.org/licenses/MIT")
                        }
                    }
                    issueManagement {
                        system.set("Github")
                        url.set("https://github.com/turbomates/kotlin-events/issues")
                    }
                    scm {
                        connection.set("scm:git:git://github.com/turbomates/kotlin-events.git")
                        developerConnection.set("scm:git:git@github.com:turbomates/kotlin-events.git")
                        url.set("https://github.com/turbomates/kotlin-event")
                    }
                    developers {
                        developer {
                            name.set("Vadim Golodko")
                            email.set("vadim@turbomates.com")
                        }
                    }
                }
            }
        }
        signing {
            sign(publishing.publications["mavenJava"])
        }
    }
}
nexusPublishing {
    repositories {
        sonatype {
            // Central Portal OSSRH Staging API URLs
            nexusUrl.set(uri("https://ossrh-staging-api.central.sonatype.com/service/local/"))
            snapshotRepositoryUrl.set(uri("https://central.sonatype.com/repository/maven-snapshots/"))

            username.set(
                System.getenv("ORG_GRADLE_PROJECT_SONATYPE_USERNAME")
                    ?: project.findProperty("centralPortalUsername")?.toString()
            )
            password.set(
                System.getenv("ORG_GRADLE_PROJECT_SONATYPE_PASSWORD")
                    ?: project.findProperty("centralPortalPassword")?.toString()
            )
        }
    }

    connectTimeout.set(Duration.ofMinutes(3))
    clientTimeout.set(Duration.ofMinutes(3))

    transitionCheckOptions {
        maxRetries.set(40)
        delayBetween.set(Duration.ofSeconds(10))
    }
}
repositories {
    mavenLocal()
    mavenCentral()
}
