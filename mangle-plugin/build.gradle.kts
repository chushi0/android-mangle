import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    `kotlin-dsl`
    `maven-publish`
}

// Oldest runtime we support is Gradle 8.7 on JDK 17, so stay below what this build's Gradle defaults to.
tasks.withType<JavaCompile>().configureEach {
    options.release = 17
}
tasks.withType<KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
        apiVersion = KotlinVersion.KOTLIN_2_0
        languageVersion = KotlinVersion.KOTLIN_2_0
    }
}

gradlePlugin {
    plugins {
        create("mangle") {
            id = "cc.cszt0.mangle"
            implementationClass = "cc.cszt0.mangle.plugin.ManglePlugin"
        }
    }
}

val generateVersionResource by tasks.registering {
    val version = project.version.toString()
    val output = layout.buildDirectory.dir("generated/version-resource")
    inputs.property("version", version)
    outputs.dir(output)
    doLast {
        output.get().file("cc/cszt0/mangle/plugin/version.txt").asFile.run {
            parentFile.mkdirs()
            writeText(version)
        }
    }
}
sourceSets.main { resources.srcDir(generateVersionResource) }

dependencies {
    compileOnly(libs.agp.api)
    implementation(libs.asm)
    implementation(libs.asm.commons)
    implementation(libs.asm.tree)
    testImplementation(libs.junit)
    testImplementation(project(":mangle-annotation"))
}

publishing {
    repositories {
        maven {
            name = "integrationTest"
            url = uri(rootProject.layout.buildDirectory.dir("integration-repo"))
        }
    }
}
