pluginManagement {
  repositories {
    gradlePluginPortal()
    mavenCentral()
  }
}

rootProject.name = "tlc"

// tcj:misk-oidc is not on Maven Central. A sibling checkout is built in place; otherwise Gradle
// clones it and builds the tag that matches the requested version.
val oidcSibling = file("../misk-oidc")
if (oidcSibling.isDirectory) {
  includeBuild(oidcSibling)
} else {
  sourceControl {
    gitRepository(uri("https://github.com/traviscj/misk-oidc.git")) { producesModule("tcj:misk-oidc") }
  }
}
