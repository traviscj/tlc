pluginManagement {
  repositories {
    gradlePluginPortal()
    mavenCentral()
  }
}

rootProject.name = "tlc"

// tcj:misk-oidc and tcj:misk-feedz are not on Maven Central. A sibling checkout is built in place;
// otherwise Gradle clones the library and builds the tag that matches the requested version.
for (lib in listOf("misk-oidc", "misk-feedz")) {
  val sibling = file("../$lib")
  if (sibling.isDirectory) {
    includeBuild(sibling)
  } else {
    sourceControl {
      gitRepository(uri("https://github.com/traviscj/$lib.git")) { producesModule("tcj:$lib") }
    }
  }
}
