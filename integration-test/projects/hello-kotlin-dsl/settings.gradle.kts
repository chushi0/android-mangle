pluginManagement {
    val mangleRepo = providers.gradleProperty("mangleRepo").get()
    repositories {
        maven { url = uri(mangleRepo) }
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    plugins {
        id("com.android.application") version providers.gradleProperty("agpVersion").get()
        id("cc.cszt0.mangle") version providers.gradleProperty("mangleVersion").get()
        id("org.jetbrains.kotlin.android") version "2.0.21"
    }
}
dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        maven { url = uri(providers.gradleProperty("mangleRepo").get()) }
        google()
        mavenCentral()
    }
}
rootProject.name = "hello-kotlin-dsl"
include(":app")
