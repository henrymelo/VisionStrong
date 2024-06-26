pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
        maven(url = "https://jitpack.io")
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven {
            url = uri("https://jitpack.io")
        }
        maven {
            url = uri("https://github.com/kshoji/BLE-MIDI-for-Android/raw/master/library/repository")
        }
        maven {
            url = uri("https://github.com/kshoji/USB-MIDI-Driver/raw/master/library/repository")
        }
    }
}

rootProject.name = "VisionStrong"
include(":app")
