plugins {
    java
    kotlin("jvm") apply true
    id("maven-publish") apply true
    id("signing") apply true
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
group = "com.turbomates"
version = System.getenv("RELEASE_VERSION") ?: "0.0.1"
subprojects {
    apply(plugin = "maven-publish")
    apply(plugin = "java")
    apply(plugin = "signing")
    java {
        withJavadocJar()
        withSourcesJar()
    }
    publishing {
        repositories {
            maven {
                val releasesUrl = uri("https://s01.oss.sonatype.org/service/local/staging/deploy/maven2/")
                val snapshotsUrl = uri("https://s01.oss.sonatype.org/content/repositories/snapshots/")
                url = if (version.toString().endsWith("SNAPSHOT")) snapshotsUrl else releasesUrl
                credentials {
                    username = System.getenv("ORG_GRADLE_PROJECT_SONATYPE_USERNAME") ?: project.properties["ossrhUsername"].toString()
                    password = System.getenv("ORG_GRADLE_PROJECT_SONATYPE_PASSWORD") ?: project.properties["ossrhPassword"].toString()
                }
            }
        }
        publications {
            create<MavenPublication>("mavenJava") {
                groupId = "com.turbomates"
                artifactId = project.name
                from(components["java"])
            }
            withType<MavenPublication> {
                pom {
                    packaging = "jar"
                    name.set(project.name)
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

repositories {
    mavenLocal()
    mavenCentral()
}
