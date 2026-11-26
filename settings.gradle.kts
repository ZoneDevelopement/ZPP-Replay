rootProject.name = "ZPP-Replay"

pluginManagement {
    repositories { gradlePluginPortal(); mavenCentral() }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        maven("https://repo.papermc.io/repository/maven-public/")
        maven("https://repo.codemc.io/repository/maven-releases/")
        maven("https://jitpack.io") {
            content { includeGroupByRegex("com\\.github\\..*") }
        }
    }
}

// Build against the local api/ checkout when the sibling ZPP-Api folder exists. Disable with -PlocalApi=false.
val localApi = settingsDir.canonicalFile.parentFile.resolve("ZPP-Api")
if (localApi.resolve("settings.gradle.kts").exists() && providers.gradleProperty("localApi").orNull != "false") {
    includeBuild(localApi) {
        dependencySubstitution {
            substitute(module("com.github.ZoneDevelopement:ZonePracticePro-Api")).using(project(":"))
        }
    }
}
