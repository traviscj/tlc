package tcj.tlc

import misk.MiskApplication
import misk.MiskRealServiceModule
import misk.config.ConfigModule
import misk.config.MiskConfig
import misk.environment.DeploymentModule
import misk.web.MiskWebModule
import wisp.deployment.DEVELOPMENT

fun main(args: Array<String>) {
  // DEVELOPMENT: SchemaMigratorService applies pending migrations at startup. Per-host settings
  // come from the override file named by TLC_CONFIG (layered over tlc-common.yaml).
  val deployment = DEVELOPMENT
  val overrides = listOfNotNull(System.getenv("TLC_CONFIG")?.takeIf { it.isNotBlank() }?.let { "filesystem:$it" })
  val config = MiskConfig.load<TlcConfig>("tlc", deployment, overrides)
  MiskApplication(
    MiskRealServiceModule(),
    MiskWebModule(config.web),
    DeploymentModule(deployment),
    ConfigModule.create("tlc", config),
    TlcModule(config),
  ).run(args)
}
