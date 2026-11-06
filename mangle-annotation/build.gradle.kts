plugins {
    `java-library`
    `maven-publish`
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 8
    options.compilerArgs.add("-Xlint:-options")
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
        }
    }
    repositories {
        maven {
            name = "integrationTest"
            url = uri(rootProject.layout.buildDirectory.dir("integration-repo"))
        }
    }
}
