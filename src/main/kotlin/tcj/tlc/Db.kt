package tcj.tlc

import com.squareup.moshi.Moshi
import java.security.SecureRandom
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Statement
import java.time.Instant
import java.util.zip.CRC32
import javax.inject.Inject
import javax.inject.Singleton
import misk.exceptions.BadRequestException
import misk.exceptions.WebActionException
import misk.jdbc.Transacter

@Singleton
class Db @Inject constructor(@TlcDb private val transacter: Transacter) {
  fun <T> tx(block: (Connection) -> T): T =
    transacter.transactionWithSession { s -> s.useConnection { c -> block(c) } }
}

private fun PreparedStatement.bindAll(args: List<Any?>) {
  args.forEachIndexed { i, a -> setObject(i + 1, a) }
}

fun Connection.exec(sql: String, args: List<Any?> = listOf()): Int =
  prepareStatement(sql).use { it.bindAll(args); it.executeUpdate() }

fun Connection.insert(sql: String, args: List<Any?>): Long =
  prepareStatement(sql, Statement.RETURN_GENERATED_KEYS).use { ps ->
    ps.bindAll(args)
    ps.executeUpdate()
    ps.generatedKeys.use { rs -> rs.next(); rs.getLong(1) }
  }

fun <T> Connection.select(sql: String, args: List<Any?> = listOf(), map: (ResultSet) -> T): List<T> =
  prepareStatement(sql).use { ps ->
    ps.bindAll(args)
    ps.executeQuery().use { rs ->
      val out = ArrayList<T>()
      while (rs.next()) out.add(map(rs))
      out
    }
  }

fun ResultSet.longOrNull(col: String): Long? = getLong(col).takeUnless { wasNull() }
fun ResultSet.isoOrNull(col: String): String? = longOrNull(col)?.let(::iso)

fun iso(ms: Long): String = Instant.ofEpochMilli(ms).toString()

/** Accepts ISO-8601 instants or epoch milliseconds. */
fun parseTime(s: String): Long =
  s.toLongOrNull() ?: try {
    Instant.parse(s).toEpochMilli()
  } catch (e: Exception) {
    throw BadRequestException("bad timestamp '$s': want ISO-8601 instant or epoch ms")
  }

private val rng = SecureRandom()
private const val ALPHABET = "abcdefghjkmnpqrstuvwxyz23456789"

/** Tokens are `<PREFIX>-<random>`; the prefix says which table to look in (see [Targets]). */
fun newToken(prefix: String, len: Int = 12): String =
  buildString(prefix.length + 1 + len) { append(prefix).append('-'); repeat(len) { append(ALPHABET[rng.nextInt(ALPHABET.length)]) } }

fun randomDigits(n: Int): String = buildString(n) { repeat(n) { append(rng.nextInt(10)) } }

fun shardOf(key: String, shards: Int): Int {
  val crc = CRC32().apply { update(key.toByteArray()) }.value
  return (crc % shards).toInt()
}

fun limitOf(limit: Int?, default: Int = 200, max: Int = 1000): Int = (limit ?: default).coerceIn(1, max)

fun String?.required(name: String): String = this?.trim()?.takeIf { it.isNotEmpty() } ?: throw BadRequestException("$name is required")

@Singleton
class Json @Inject constructor(moshi: Moshi) {
  private val any = moshi.adapter(Any::class.java).serializeNulls()
  fun write(v: Any?): String = any.toJson(v)
  fun parse(s: String): Any? = any.fromJson(s)
}

/**
 * 403 with the reason in the body. misk's UnauthorizedException replies with a bare "unauthorized";
 * here the reason ("you requested it", "not in an approving group") is the useful part and reveals
 * nothing the caller couldn't read from the rule anyway.
 */
fun forbidden(message: String): Nothing = throw WebActionException(403, message, message)
