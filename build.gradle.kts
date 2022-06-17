import io.gitlab.arturbosch.detekt.Detekt
import io.gitlab.arturbosch.detekt.report.ReportMergeTask

plugins {
    kotlin("jvm") apply true
    alias(deps.plugins.detekt)
    alias(deps.plugins.nexus)
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

//subprojects {
//    tasks.withType<Detekt>().configureEach detekt@{
//        finalizedBy(reportMerge)
//        reportMerge.configure {
//            input.from(this@detekt.xmlReportFile)
//        }
//    }
//}
//
//nexusPublishing {
//    repositories {
//        sonatype {
//            username.set(System.getenv("exposed.sonatype.user"))
//            password.set(System.getenv("exposed.sonatype.password"))
//            useStaging.set(true)
//        }
//    }
//}

repositories {
    mavenLocal()
    mavenCentral()
}
