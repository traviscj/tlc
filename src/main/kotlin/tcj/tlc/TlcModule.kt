package tcj.tlc

import misk.ServiceModule
import misk.inject.KAbstractModule
import misk.jdbc.JdbcModule
import misk.jdbc.SchemaMigratorService
import misk.security.authz.AccessControlModule
import misk.security.authz.MiskCallerAuthenticator
import misk.web.WebActionModule

/** Everything except deployment + web server wiring, which differ between app and tests. */
class TlcModule(private val config: TlcConfig) : KAbstractModule() {
  override fun configure() {
    // Without this, @Authenticated is decorative: misk only enforces it via AccessInterceptor.
    install(AccessControlModule())
    multibind<MiskCallerAuthenticator>().to<TlcAuthenticator>()

    install(JdbcModule(TlcDb::class, config.data_source))
    install(ServiceModule<Background>().dependsOn<SchemaMigratorService>(TlcDb::class))
    bind<NotificationTransport>().to<DefaultTransport>()

    install(WebActionModule.create<WebActions>())
    install(WebActionModule.create<InfoActions>())
    install(WebActionModule.create<EntityTypeActions>())
    install(WebActionModule.create<EntityActions>())
    install(WebActionModule.create<TlcerActions>())
    install(WebActionModule.create<TagActions>())
    install(WebActionModule.create<TaggingActions>())
    install(WebActionModule.create<LogActions>())
    install(WebActionModule.create<ThreadActions>())
    install(WebActionModule.create<CommentActions>())
    install(WebActionModule.create<DirectoryActions>())
    install(WebActionModule.create<RuleActions>())
    install(WebActionModule.create<ApprovableActions>())
    install(WebActionModule.create<OwnershipActions>())
    install(WebActionModule.create<EdgeActions>())
    install(WebActionModule.create<FeedActions>())
  }
}
