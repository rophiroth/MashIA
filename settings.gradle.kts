pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "MashIA"
include(":app")

<<<<<<< HEAD
// Enable Java Toolchains auto-download (via Foojay resolver)
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}
=======
>>>>>>> 066957aeb982b01080f077862cfa8d4e3bbbf5ec
