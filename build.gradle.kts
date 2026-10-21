plugins {
  alias(libs.plugins.kotlin.jvm)
  application
}

group = "io.github.sparklingstadt.typle"
version = "0.58.0"

dependencies {
  implementation(libs.ktor.server.core)
  implementation(libs.ktor.server.cio)
  implementation(libs.postgresql)
  implementation(libs.hikari)
  runtimeOnly(libs.slf4j.simple)
  testImplementation(kotlin("test"))
}

kotlin { jvmToolchain(21) }

application {
  mainClass = "io.github.sparklingstadt.typle.server.MainKt"
  applicationName = "typle-web"
}

tasks.test {
  useJUnitPlatform()
  // The store tests run against this database when it is set, and are skipped otherwise.
  System.getenv("TYPLE_TEST_DATABASE_URL")?.let { environment("TYPLE_TEST_DATABASE_URL", it) }
  testLogging { events("failed"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}
