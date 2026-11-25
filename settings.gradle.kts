pluginManagement {
    // Maven Central (repo.maven.apache.org) can't be reached from the development network: it answers 404.
    // Google's official mirror of Maven Central (same files) is listed first, so builds don't depend on it;
    // mavenCentral() stays as a fallback for other networks. (This block can't use variables: Gradle reads it first.)
    repositories {
        google()
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
        mavenCentral()
        gradlePluginPortal()
    }
}

/** Google's mirror of Maven Central (see pluginManagement above). */
val mavenCentralMirror = "https://maven-central.storage-download.googleapis.com/maven2/"

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        maven(mavenCentralMirror)
        mavenCentral()
    }
}
rootProject.name = "MDM_App"
include(":app")
