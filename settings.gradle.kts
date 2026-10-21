// The app's backend in Kotlin: the same API as the Go typle-web (server/cmd/typle-web), for local development and the
// end-to-end tests only. Production runs the Go functions.
rootProject.name = "typle-web"

pluginManagement {
  repositories {
    gradlePluginPortal()
    mavenCentral()
  }
}

dependencyResolutionManagement {
  repositories { mavenCentral() }
}
