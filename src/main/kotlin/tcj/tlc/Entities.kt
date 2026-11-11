package tcj.tlc

import java.sql.Connection
import java.sql.ResultSet
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton
import misk.exceptions.BadRequestException
import misk.exceptions.ConflictException
import misk.exceptions.NotFoundException
import misk.security.authz.Authenticated
import misk.web.Delete
import misk.web.Get
import misk.web.Patch
import misk.web.PathParam
import misk.web.Post
import misk.web.QueryParam
import misk.web.RequestBody
import misk.web.RequestContentType
import misk.web.ResponseContentType
import misk.web.actions.WebAction
import misk.web.mediatype.MediaTypes

const val JSON = MediaTypes.APPLICATION_JSON

data class Ok(val ok: Boolean = true)

data class EntityTypeDto(
  val token: String,
  val source_service: String,
  val source_entity: String,
  val description: String?,
  /** `{id}` is replaced by source_entity_id to link back to the owning service's UI. */
  val url_template: String?,
  val entities: Long,
  val created_at: String,
  val updated_at: String,
  val deleted_at: String?,
  val version: Int,
)

data class EntityDto(
  val token: String,
  /** Canonical `service/entity/id` ref; accepted anywhere a `target` is. */
  val ref: String,
  val type_token: String,
  val source_service: String,
  val source_entity: String,
  val source_entity_id: String,
  val display_name: String?,
  val url: String?,
  val created_at: String,
  val updated_at: String,
  val deleted_at: String?,
  val version: Int,
)

/**
 * What a `target` string resolved to. Exactly one of [entityId] (an external record) and
 * [subjectToken] (a TLC-internal object: thread T-, comment C-, approvable AP-) is set.
 * [key] is the single indexable form: `E:<id>` or the token.
 */
data class Target(val entityId: Long?, val entityTypeId: Long?, val subjectToken: String?, val ref: String) {
  val key: String get() = if (entityId != null) "E:$entityId" else subjectToken!!
}

private val NAME_RE = Regex("[A-Za-z0-9][A-Za-z0-9_.-]{0,95}")

@Singleton
class EntityStore @Inject constructor(
  private val db: Db,
  private val clock: Clock,
  private val config: TlcConfig,
) {
  private val typeSelect = """
    SELECT t.*, (SELECT COUNT(*) FROM external_entities e WHERE e.external_entity_type_id = t.id AND e.deleted_at_ms IS NULL) AS n
    FROM external_entity_types t
  """.trimIndent()

  private fun type(rs: ResultSet) = EntityTypeDto(
    rs.getString("token"), rs.getString("source_service"), rs.getString("source_entity"), rs.getString("description"),
    rs.getString("url_template"), rs.getLong("n"), iso(rs.getLong("created_at_ms")), iso(rs.getLong("updated_at_ms")),
    rs.isoOrNull("deleted_at_ms"), rs.getInt("version"),
  )

  private val entitySelect = """
    SELECT e.*, t.token AS type_token, t.source_service, t.source_entity, t.url_template
    FROM external_entities e JOIN external_entity_types t ON t.id = e.external_entity_type_id
  """.trimIndent()

  private fun entity(rs: ResultSet): EntityDto {
    val sid = rs.getString("source_entity_id")
    return EntityDto(
      rs.getString("token"), "${rs.getString("source_service")}/${rs.getString("source_entity")}/$sid", rs.getString("type_token"),
      rs.getString("source_service"), rs.getString("source_entity"), sid, rs.getString("display_name"),
      rs.getString("url_template")?.replace("{id}", sid), iso(rs.getLong("created_at_ms")), iso(rs.getLong("updated_at_ms")),
      rs.isoOrNull("deleted_at_ms"), rs.getInt("version"),
    )
  }

  // ------------------------------------------------------------------------------- types

  fun createType(service: String, entity: String, description: String?, urlTemplate: String?): EntityTypeDto = db.tx { c ->
    if (!NAME_RE.matches(service) || !NAME_RE.matches(entity)) throw BadRequestException("source_service / source_entity must match ${NAME_RE.pattern}")
    val existing = c.select("SELECT id, deleted_at_ms FROM external_entity_types WHERE source_service = ? AND source_entity = ? FOR UPDATE", listOf(service, entity)) { it.getLong(1) to it.longOrNull("deleted_at_ms") }.firstOrNull()
    val now = clock.millis()
    if (existing == null) {
      c.insert(
        "INSERT INTO external_entity_types (created_at_ms, updated_at_ms, token, source_service, source_entity, description, url_template) VALUES (?,?,?,?,?,?,?)",
        listOf(now, now, newToken("ET"), service, entity, description, urlTemplate),
      )
    } else if (existing.second != null) {
      // Re-registering a destroyed type revives it (and every record that hung off it).
      c.exec("UPDATE external_entity_types SET deleted_at_ms = NULL, description = COALESCE(?, description), url_template = COALESCE(?, url_template), updated_at_ms = ?, version = version + 1 WHERE id = ?", listOf(description, urlTemplate, now, existing.first))
    } else {
      throw ConflictException("entity type $service/$entity is already registered")
    }
    c.select("$typeSelect WHERE t.source_service = ? AND t.source_entity = ?", listOf(service, entity), ::type).first()
  }

  fun listTypes(includeDeleted: Boolean): List<EntityTypeDto> = db.tx { c ->
    c.select("$typeSelect ${if (includeDeleted) "" else "WHERE t.deleted_at_ms IS NULL"} ORDER BY t.source_service, t.source_entity", map = ::type)
  }

  /** [ref] is a type token (`ET-…`) or `service/entity`. */
  fun getType(ref: String): EntityTypeDto = db.tx { c -> typeByRef(c, ref) }

  private fun typeByRef(c: Connection, ref: String): EntityTypeDto {
    val parts = ref.split('/')
    val rows = if (parts.size == 2) c.select("$typeSelect WHERE t.source_service = ? AND t.source_entity = ?", parts, ::type)
    else c.select("$typeSelect WHERE t.token = ?", listOf(ref), ::type)
    return rows.firstOrNull() ?: throw NotFoundException("no entity type '$ref'")
  }

  fun typeId(c: Connection, ref: String): Long {
    val t = typeByRef(c, ref)
    return c.select("SELECT id FROM external_entity_types WHERE token = ?", listOf(t.token)) { it.getLong(1) }.first()
  }

  fun updateType(ref: String, description: String?, urlTemplate: String?): EntityTypeDto = db.tx { c ->
    val t = typeByRef(c, ref)
    c.exec("UPDATE external_entity_types SET description = COALESCE(?, description), url_template = COALESCE(?, url_template), updated_at_ms = ?, version = version + 1 WHERE token = ?", listOf(description, urlTemplate, clock.millis(), t.token))
    typeByRef(c, t.token)
  }

  /** Soft: tags/logs/comments already hung on its records stay queryable; new refs 404. */
  fun destroyType(ref: String): EntityTypeDto = db.tx { c ->
    val t = typeByRef(c, ref)
    if (t.deleted_at == null) c.exec("UPDATE external_entity_types SET deleted_at_ms = ?, updated_at_ms = ?, version = version + 1 WHERE token = ?", listOf(clock.millis(), clock.millis(), t.token))
    typeByRef(c, t.token)
  }

  // ---------------------------------------------------------------------------- entities

  /**
   * Find-or-create the record behind `service/entity/id`. Records are vivified on first reference
   * (TLC can't know every row of someone else's table up front); types are not, unless
   * `auto_register_entity_types` is on.
   */
  fun ensure(c: Connection, service: String, entityName: String, sourceId: String, displayName: String? = null, create: Boolean = true): Pair<Long, Long>? {
    if (sourceId.isBlank() || sourceId.length > 191) throw BadRequestException("source_entity_id must be 1..191 chars")
    var type = c.select("SELECT id, deleted_at_ms FROM external_entity_types WHERE source_service = ? AND source_entity = ?", listOf(service, entityName)) { it.getLong(1) to it.longOrNull("deleted_at_ms") }.firstOrNull()
    val now = clock.millis()
    if (type == null && create && config.tlc.auto_register_entity_types) {
      if (!NAME_RE.matches(service) || !NAME_RE.matches(entityName)) throw BadRequestException("bad entity type $service/$entityName")
      c.exec("INSERT IGNORE INTO external_entity_types (created_at_ms, updated_at_ms, token, source_service, source_entity) VALUES (?,?,?,?,?)", listOf(now, now, newToken("ET"), service, entityName))
      type = c.select("SELECT id, deleted_at_ms FROM external_entity_types WHERE source_service = ? AND source_entity = ?", listOf(service, entityName)) { it.getLong(1) to it.longOrNull("deleted_at_ms") }.first()
    }
    if (type == null) {
      if (!create) return null
      throw NotFoundException("entity type $service/$entityName is not registered (POST /api/entity-types first)")
    }
    val find = { c.select("SELECT id, deleted_at_ms FROM external_entities WHERE external_entity_type_id = ? AND source_entity_id = ?", listOf(type.first, sourceId)) { it.getLong(1) to it.longOrNull("deleted_at_ms") }.firstOrNull() }
    var e = find()
    if (e == null) {
      if (!create) return null
      if (type.second != null) throw NotFoundException("entity type $service/$entityName was destroyed")
      c.exec("INSERT IGNORE INTO external_entities (created_at_ms, updated_at_ms, token, external_entity_type_id, source_entity_id, display_name) VALUES (?,?,?,?,?,?)", listOf(now, now, newToken("E"), type.first, sourceId, displayName))
      e = find()!!
    } else if (e.second != null && create) {
      // A write against a destroyed record means the source service still has it: revive.
      c.exec("UPDATE external_entities SET deleted_at_ms = NULL, updated_at_ms = ?, version = version + 1 WHERE id = ?", listOf(now, e.first))
    }
    return e.first to type.first
  }

  fun create(target: String?, service: String?, entityName: String?, sourceId: String?, displayName: String?): EntityDto = db.tx { c ->
    val (s, en, sid) = if (target != null) splitRef(target) ?: throw BadRequestException("target must look like service/entity/id")
    else Triple(service.required("source_service"), entityName.required("source_entity"), sourceId.required("source_entity_id"))
    val (id, _) = ensure(c, s, en, sid, displayName)!!
    if (displayName != null) c.exec("UPDATE external_entities SET display_name = ?, updated_at_ms = ?, version = version + 1 WHERE id = ? AND NOT (display_name <=> ?)", listOf(displayName, clock.millis(), id, displayName))
    byId(c, id)
  }

  fun byId(c: Connection, id: Long): EntityDto = c.select("$entitySelect WHERE e.id = ?", listOf(id), ::entity).first()

  fun refOf(c: Connection, id: Long): String = byId(c, id).ref

  fun get(ref: String): EntityDto = db.tx { c ->
    val t = resolveEntity(c, ref, create = false)
    byId(c, t.entityId!!)
  }

  fun list(type: String?, q: String?, includeDeleted: Boolean, limit: Int): List<EntityDto> = db.tx { c ->
    val where = ArrayList<String>()
    val args = ArrayList<Any?>()
    if (!includeDeleted) where += "e.deleted_at_ms IS NULL"
    if (type != null) { where += "e.external_entity_type_id = ?"; args += typeId(c, type) }
    if (!q.isNullOrBlank()) { where += "(e.source_entity_id LIKE ? OR e.display_name LIKE ?)"; args += "%$q%"; args += "%$q%" }
    c.select("$entitySelect ${if (where.isEmpty()) "" else "WHERE " + where.joinToString(" AND ")} ORDER BY e.id DESC LIMIT $limit", args, ::entity)
  }

  fun update(ref: String, displayName: String?): EntityDto = db.tx { c ->
    val id = resolveEntity(c, ref, create = false).entityId!!
    c.exec("UPDATE external_entities SET display_name = ?, updated_at_ms = ?, version = version + 1 WHERE id = ?", listOf(displayName, clock.millis(), id))
    byId(c, id)
  }

  fun destroy(ref: String): EntityDto = db.tx { c ->
    val id = resolveEntity(c, ref, create = false).entityId!!
    c.exec("UPDATE external_entities SET deleted_at_ms = ?, updated_at_ms = ?, version = version + 1 WHERE id = ? AND deleted_at_ms IS NULL", listOf(clock.millis(), clock.millis(), id))
    byId(c, id)
  }

  // ----------------------------------------------------------------------------- targets

  private fun splitRef(ref: String): Triple<String, String, String>? {
    val p = ref.split('/', limit = 3)
    return if (p.size == 3 && p.all { it.isNotEmpty() }) Triple(p[0], p[1], p[2]) else null
  }

  /** `service/entity/id` or an `E-…` token -> an external-entity target. */
  fun resolveEntity(c: Connection, ref: String, create: Boolean): Target {
    val r = ref.trim()
    if (r.startsWith("E-")) {
      val row = c.select("SELECT id, external_entity_type_id FROM external_entities WHERE token = ?", listOf(r)) { it.getLong(1) to it.getLong(2) }.firstOrNull()
        ?: throw NotFoundException("no entity '$r'")
      return Target(row.first, row.second, null, refOf(c, row.first))
    }
    val (s, en, sid) = splitRef(r) ?: throw BadRequestException("'$r' is not an entity ref: want service/entity/id or an E- token")
    val ids = ensure(c, s, en, sid, create = create) ?: throw NotFoundException("TLC has no record of $r")
    return Target(ids.first, ids.second, null, "$s/$en/$sid")
  }

  /**
   * Any target: an external entity (as above) or a TLC-internal object by token (T- thread,
   * C- comment, AP- approvable). With [create] false nothing is vivified: unknown refs are a 404,
   * which is what read paths want.
   */
  fun resolve(c: Connection, ref: String, create: Boolean): Target {
    val r = ref.trim()
    val table = when {
      r.startsWith("T-") -> "threads" to "token"
      r.startsWith("C-") -> "comments" to "comment_token"
      r.startsWith("AP-") -> "approvables" to "token"
      else -> return resolveEntity(c, r, create)
    }
    c.select("SELECT id FROM ${table.first} WHERE ${table.second} = ?", listOf(r)) { it.getLong(1) }.firstOrNull() ?: throw NotFoundException("no ${table.first.dropLast(1)} '$r'")
    return Target(null, null, r, r)
  }

  /** Read-path variant: null when the ref is well-formed but TLC has never seen it. */
  fun resolveOrNull(c: Connection, ref: String): Target? = try {
    resolve(c, ref, create = false)
  } catch (e: NotFoundException) {
    null
  }

  /** Inverse of [Target.key], for rendering rows that only stored the key. */
  fun refOfKey(c: Connection, key: String): String = if (key.startsWith("E:")) refOf(c, key.substring(2).toLong()) else key
}

// ------------------------------------------------------------------------------------ actions

data class EntityTypeReq(val source_service: String?, val source_entity: String?, val description: String?, val url_template: String?)
data class EntityTypeList(val entity_types: List<EntityTypeDto>)
data class EntityReq(val target: String?, val source_service: String?, val source_entity: String?, val source_entity_id: String?, val display_name: String?)
data class EntityList(val entities: List<EntityDto>)
data class TlcerList(val tlcers: List<TlcerDto>)
data class MeDto(val ldap: String?, val service: String?, val admin: Boolean, val directory_groups: List<String>)
data class MeReq(val display_name: String?)

class EntityTypeActions @Inject constructor(private val store: EntityStore) : WebAction {
  @Post("/api/entity-types") @Authenticated(capabilities = [USER]) @RequestContentType(JSON) @ResponseContentType(JSON)
  fun create(@RequestBody req: EntityTypeReq) = store.createType(req.source_service.required("source_service"), req.source_entity.required("source_entity"), req.description, req.url_template)

  @Get("/api/entity-types") @Authenticated(capabilities = [USER]) @ResponseContentType(JSON)
  fun list(@QueryParam include_deleted: Boolean?) = EntityTypeList(store.listTypes(include_deleted == true))

  /** `type` is an ET- token or `service/entity`. */
  @Get("/api/entity-types/get") @Authenticated(capabilities = [USER]) @ResponseContentType(JSON)
  fun get(@QueryParam type: String) = store.getType(type)

  @Patch("/api/entity-types") @Authenticated(capabilities = [USER]) @RequestContentType(JSON) @ResponseContentType(JSON)
  fun update(@QueryParam type: String, @RequestBody req: EntityTypeReq) = store.updateType(type, req.description, req.url_template)

  @Delete("/api/entity-types") @Authenticated(capabilities = [ADMIN]) @ResponseContentType(JSON)
  fun destroy(@QueryParam type: String) = store.destroyType(type)
}

class EntityActions @Inject constructor(private val store: EntityStore) : WebAction {
  @Post("/api/entities") @Authenticated(capabilities = [USER]) @RequestContentType(JSON) @ResponseContentType(JSON)
  fun create(@RequestBody req: EntityReq) = store.create(req.target, req.source_service, req.source_entity, req.source_entity_id, req.display_name)

  @Get("/api/entities") @Authenticated(capabilities = [USER]) @ResponseContentType(JSON)
  fun list(@QueryParam type: String?, @QueryParam q: String?, @QueryParam include_deleted: Boolean?, @QueryParam limit: Int?) =
    EntityList(store.list(type, q, include_deleted == true, limitOf(limit)))

  @Get("/api/entities/get") @Authenticated(capabilities = [USER]) @ResponseContentType(JSON)
  fun get(@QueryParam target: String) = store.get(target)

  @Patch("/api/entities") @Authenticated(capabilities = [USER]) @RequestContentType(JSON) @ResponseContentType(JSON)
  fun update(@QueryParam target: String, @RequestBody req: EntityReq) = store.update(target, req.display_name)

  @Delete("/api/entities") @Authenticated(capabilities = [USER]) @ResponseContentType(JSON)
  fun destroy(@QueryParam target: String) = store.destroy(target)
}

class TlcerActions @Inject constructor(private val tlcers: Tlcers, private val actors: Actors, private val directory: Directory) : WebAction {
  @Get("/api/tlcers") @Authenticated(capabilities = [USER]) @ResponseContentType(JSON)
  fun list() = TlcerList(tlcers.list())

  @Get("/api/me") @Authenticated(capabilities = [USER]) @ResponseContentType(JSON)
  fun me(): MeDto = actors.current().let { a -> MeDto(a.ldap, a.service, a.admin, a.ldap?.let(directory::groupsOf).orEmpty()) }

  @Patch("/api/me") @Authenticated(capabilities = [USER]) @RequestContentType(JSON) @ResponseContentType(JSON)
  fun update(@RequestBody req: MeReq): Ok {
    tlcers.setDisplayName(actors.current(), req.display_name)
    return Ok()
  }
}
