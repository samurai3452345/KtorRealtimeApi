package ru.student.realtime

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.ktor.client.plugins.websocket.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.server.testing.*
import io.ktor.websocket.*
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.Date
import kotlin.test.*

class RealtimeTest {
    private val settings = Settings("jdbc:unused", "test", "test", "socket-test-secret-".repeat(3), adminPassword = "test-admin-password")

    @Test
    fun `chat broadcasts persisted messages while notifications go only to their owner`() = testApplication {
        val store = InMemoryStore()
        val admin = store.createUser("admin", "unused", Role.admin)
        val alice = store.createUser("alice", "unused")
        val bob = store.createUser("bob", "unused")
        val tokens = TokenService(settings)
        application { module(settings, store) }
        val sockets = createClient { install(WebSockets) }
        val a = sockets.webSocketSession("/ws")
        val b = sockets.webSocketSession("/ws")
        try {
            a.authenticate(tokens.issue(alice).accessToken)
            b.authenticate(tokens.issue(bob).accessToken)
            a.send(Frame.Text("""{"type":"message","text":"Привет из WebSocket"}"""))
            val aMessage = a.nextEvent()
            val bMessage = b.nextEvent()
            assertEquals("message", aMessage.getValue("type").jsonPrimitive.content)
            assertEquals(aMessage, bMessage)
            val saved = aMessage.getValue("message").jsonObject
            assertEquals(alice.id, saved.getValue("userId").jsonPrimitive.long)
            assertEquals("Привет из WebSocket", store.listMessages(null, 50).single().text)

            val response = client.post("/api/admin/notifications") {
                bearerAuth(tokens.issue(admin).accessToken)
                contentType(ContentType.Application.Json)
                setBody("""{"title":"Личное","message":"Только Алисе","userId":${alice.id}}""")
            }
            assertEquals(HttpStatusCode.Created, response.status)
            val event = a.nextEvent()
            assertEquals("notification", event.getValue("type").jsonPrimitive.content)
            assertEquals(alice.id, event.getValue("notification").jsonObject.getValue("userId").jsonPrimitive.long)
            b.send(Frame.Text("""{"type":"message","text":"Следующее сообщение"}"""))
            assertEquals("message", b.nextEvent().getValue("type").jsonPrimitive.content)
            assertTrue(store.listNotifications(bob.id, 100).isEmpty())
        } finally { a.close(); b.close(); sockets.close() }
    }

    @Test
    fun `invalid authentication closes the socket without accepting chat messages`() = testApplication {
        val store = InMemoryStore()
        application { module(settings, store) }
        val sockets = createClient { install(WebSockets) }
        for (frame in listOf("""{"type":"auth","token":"invalid.token"}""", """{"type":"message","text":"anonymous"}""")) {
            val session = sockets.webSocketSession("/ws")
            session.send(Frame.Text(frame))
            val reason = withTimeout(5000) { session.closeReason.await() }
            assertEquals(CloseReason.Codes.VIOLATED_POLICY.code, reason?.code)
            assertTrue(store.listMessages(null, 50).isEmpty())
        }
        sockets.close()
    }

    @Test
    fun `malformed and empty frames return errors and the connection remains usable`() = testApplication {
        val store = InMemoryStore()
        val user = store.createUser("alice", "unused")
        application { module(settings, store) }
        val sockets = createClient { install(WebSockets) }
        val session = sockets.webSocketSession("/ws")
        try {
            session.authenticate(TokenService(settings).issue(user).accessToken)
            for (frame in listOf("{broken", """{"type":"message","text":"  "}""", """{"type":"auth","token":"again"}""")) {
                session.send(Frame.Text(frame))
                assertEquals("error", session.nextEvent().getValue("type").jsonPrimitive.content)
            }
            session.send(Frame.Text("""{"type":"message","text":"Valid"}"""))
            assertEquals("message", session.nextEvent().getValue("type").jsonPrimitive.content)
            assertEquals(1, store.listMessages(null, 50).size)
        } finally { session.close(); sockets.close() }
    }

    @Test
    fun `expired JWT is rejected by both REST and WebSocket`() = testApplication {
        val store = InMemoryStore()
        val user = store.createUser("alice", "unused")
        application { module(settings, store) }
        val token = JWT.create().withIssuer(settings.issuer).withAudience(settings.audience)
            .withSubject(user.id.toString()).withExpiresAt(Date.from(Instant.now().minusSeconds(60)))
            .sign(Algorithm.HMAC256(settings.jwtSecret))
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/users/me") { bearerAuth(token) }.status)
        val sockets = createClient { install(WebSockets) }
        val session = sockets.webSocketSession("/ws")
        session.send(Frame.Text(buildJsonObject { put("type", "auth"); put("token", token) }.toString()))
        assertEquals(CloseReason.Codes.VIOLATED_POLICY.code, withTimeout(5000) { session.closeReason.await() }?.code)
        sockets.close()
    }

    private suspend fun DefaultClientWebSocketSession.authenticate(token: String) {
        send(Frame.Text(buildJsonObject { put("type", "auth"); put("token", token) }.toString()))
        assertEquals("ready", nextEvent().getValue("type").jsonPrimitive.content)
    }

    private suspend fun DefaultClientWebSocketSession.nextEvent(): JsonObject = withTimeout(5000) {
        Json.parseToJsonElement((incoming.receive() as Frame.Text).readText()).jsonObject
    }
}
