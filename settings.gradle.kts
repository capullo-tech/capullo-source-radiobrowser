pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // capullo-audio-contracts (published) + NewPipeExtractor (com.github.TeamNewPipe).
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "capullo-source-radiobrowser"
include(":capullo-source-radiobrowser")
include(":app") // harness/demo app: exercises RadioBrowserSource against the SPI.

// Dev/release toggle: when the SPI repo is checked out as a sibling (local co-development or the CI
// sibling-checkout), build it from source via a composite build and substitute it for the jitpack
// coordinate `com.github.capullo-tech:capullo-audio-contracts`. On jitpack (single repo, no sibling)
// this block is skipped and the coordinate resolves from jitpack.io instead.
if (file("../capullo-audio-contracts").exists()) {
    includeBuild("../capullo-audio-contracts") {
        dependencySubstitution {
            substitute(module("com.github.capullo-tech:capullo-audio-contracts"))
                .using(project(":"))
        }
    }
}
