package ru.student.realtime

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.exceptions.JWTVerificationException
import com.auth0.jwt.interfaces.DecodedJWT
import com.auth0.jwt.interfaces.JWTVerifier
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.response.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import java.util.Date
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

object Passwords {
    private const val iterations = 210_000
    private val random = SecureRandom()

    suspend fun hash(password: String): String = withContext(Dispatchers.Default) {
        val salt = ByteArray(16).also(random::nextBytes)
        val encoder = Base64.getEncoder()
        "pbkdf2_sha256\$$iterations\$${encoder.encodeToString(salt)}\$${encoder.encodeToString(derive(password, salt, iterations))}"
    }

    suspend fun verify(password: String, encoded: String): Boolean = withContext(Dispatchers.Default) {
        try {
            val parts = encoded.split('$')
            if (parts.size != 4 || parts[0] != "pbkdf2_sha256") return@withContext false
            val rounds = parts[1].toInt()
            if (rounds !in 100_000..1_000_000) return@withContext false
            val decoder = Base64.getDecoder()
            MessageDigest.isEqual(decoder.decode(parts[3]), derive(password, decoder.decode(parts[2]), rounds))
        } catch (_: IllegalArgumentException) {
            false
        }
    }

    private fun derive(password: String, salt: ByteArray, rounds: Int): ByteArray {
        val spec = PBEKeySpec(password.toCharArray(), salt, rounds, 256)
        return try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded }
        finally { spec.clearPassword() }
    }
}

class TokenService(private val settings: Settings) {
    private val algorithm = Algorithm.HMAC256(settings.jwtSecret)
    val verifier: JWTVerifier = JWT.require(algorithm).withIssuer(settings.issuer)
        .withAudience(settings.audience).withClaimPresence("exp").build()

    fun issue(user: UserRecord): TokenResponse {
        val now = Instant.now()
        val token = JWT.create().withIssuer(settings.issuer).withAudience(settings.audience)
            .withSubject(user.id.toString()).withClaim("role", user.role.name)
            .withIssuedAt(Date.from(now)).withExpiresAt(Date.from(now.plusSeconds(settings.jwtTtlSeconds)))
            .sign(algorithm)
        return TokenResponse(token, settings.jwtTtlSeconds, user.toDto())
    }

    fun verify(token: String): DecodedJWT? = try {
        verifier.verify(token).takeIf { it.subject?.toLongOrNull()?.let { id -> id > 0 } == true }
    } catch (_: JWTVerificationException) { null }

    suspend fun authenticate(token: String, store: AppStore): UserRecord? {
        val jwt = verify(token) ?: return null
        return store.findUserById(jwt.subject.toLong())
    }
}

data class UserPrincipal(val user: UserRecord)

fun Application.configureAuthentication(store: AppStore, tokens: TokenService) {
    install(Authentication) {
        jwt("jwt") {
            realm = "KT4 API"
            verifier(tokens.verifier)
            validate { credential ->
                val id = credential.payload.subject?.toLongOrNull()
                if (id == null || id <= 0) null else store.findUserById(id)?.let(::UserPrincipal)
            }
            challenge { _, _ -> call.respond(HttpStatusCode.Unauthorized, ApiError("Требуется действующий JWT Bearer токен")) }
        }
    }
}

suspend fun bootstrapAdmin(store: AppStore, settings: Settings) {
    if (store.findUserByUsername(settings.adminUsername) == null) {
        store.createUser(settings.adminUsername, Passwords.hash(settings.adminPassword), Role.admin)
    }
}
