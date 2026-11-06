plugins {
    kotlin("jvm") version embeddedKotlinVersion
}

dependencies {
    testImplementation(gradleTestKit())
    testImplementation(libs.junit)
}

tasks.test {
    dependsOn(":mangle-annotation:publishAllPublicationsToIntegrationTestRepository")
    dependsOn(":mangle-plugin:publishAllPublicationsToIntegrationTestRepository")
    inputs.dir("projects")
    outputs.upToDateWhen { false }
    maxHeapSize = "512m"
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = true
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
    systemProperty("mangle.repo", rootProject.layout.buildDirectory.dir("integration-repo").get().asFile.path)
    systemProperty("mangle.version", project.version.toString())
    systemProperty("mangle.projects", file("projects").path)
    systemProperty("mangle.workDir", layout.buildDirectory.dir("fixtures").get().asFile.path)
    systemProperty("mangle.gradleUserHome", gradle.gradleUserHomeDir.path)
    systemProperty("mangle.currentGradle", gradle.gradleVersion)
    // agp:gradle[:javaHome] entries separated by commas, e.g. -Pmangle.matrix=8.5.0:8.13:/path/to/jdk21
    systemProperty("mangle.matrix", providers.gradleProperty("mangle.matrix").getOrElse("9.5.0:${gradle.gradleVersion}"))
}
