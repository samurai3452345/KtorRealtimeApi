package ru.student.realtime

import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.swagger.v3.parser.OpenAPIV3Parser
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ApiTest {
    @Test
    fun `registration normalizes the username and returns a usable user token`() = withApi { _, _ ->
        val response = client.postJson("/api/auth/register", credentials("Alice"))
        assertEquals(HttpStatusCode.Created, response.status)
        val body = response.objectBody()
        assertEquals("Bearer", body.getValue("tokenType").jsonPrimitive.content)
        assertTrue(body.getValue("expiresIn").jsonPrimitive.long > 0)
        val user = body.getValue("user").jsonObject
        assertEquals("alice", user.getValue("username").jsonPrimitive.content)
        assertEquals("user", user.getValue("role").jsonPrimitive.content)
        assertFalse("password" in user)
        assertFalse("passwordHash" in user)
        val token = body.getValue("accessToken").jsonPrimitive.content
        val me = client.get("/api/users/me") { bearerAuth(token) }
        assertEquals(HttpStatusCode.OK, me.status)
        assertEquals(user, me.objectBody())
    }

    @Test
    fun `registration rejects role injection duplicate names and invalid credentials`() = withApi { _, _ ->
        val escalated = client.postJson("/api/auth/register", buildJsonObject {
            put("username", "intruder")
            put("password", TEST_PASSWORD)
            put("role", "admin")
        })
        assertEquals(HttpStatusCode.BadRequest, escalated.status)
        register("alice")
        assertEquals(HttpStatusCode.Conflict, client.postJson("/api/auth/register", credentials("ALICE")).status)
        for (username in listOf("ab", "bad name", "юзер", "a".repeat(33))) {
            assertEquals(HttpStatusCode.BadRequest,
                client.postJson("/api/auth/register", credentials(username)).status, username)
        }
        for (password in listOf("short", "x".repeat(129))) {
            assertEquals(HttpStatusCode.BadRequest,
                client.postJson("/api/auth/register", credentials("valid_user", password)).status)
        }
        assertEquals(HttpStatusCode.BadRequest, client.post("/api/auth/register") {
            contentType(ContentType.Application.Json)
            setBody("{broken")
        }.status)
        assertEquals(HttpStatusCode.BadRequest,
            client.postJson("/api/auth/register", buildJsonObject { put("username", "missing_password") }).status)
    }

    @Test
    fun `login and protected endpoints enforce JWT and roles`() = withApi { settings, _ ->
        val alice = register("alice")
        assertEquals(HttpStatusCode.Unauthorized,
            client.postJson("/api/auth/login", credentials("alice", "wrong-password")).status)
        assertEquals(HttpStatusCode.Unauthorized,
            client.postJson("/api/auth/login", credentials("missing_user")).status)
        val login = client.postJson("/api/auth/login", credentials("ALICE"))
        assertEquals(HttpStatusCode.OK, login.status)
        assertEquals(alice.id, login.objectBody().getValue("user").jsonObject.getValue("id").jsonPrimitive.long)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/users/me").status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/messages") { bearerAuth("invalid.token") }.status)
        assertEquals(HttpStatusCode.Forbidden, client.get("/api/admin/users") { bearerAuth(alice.token) }.status)
        val admin = adminToken(settings)
        assertEquals(HttpStatusCode.OK, client.get("/api/admin/users") { bearerAuth(admin) }.status)
    }

    @Test
    fun `role changes apply to existing tokens and admin cannot demote self`() = withApi { settings, _ ->
        val alice = register("alice")
        val admin = adminToken(settings)
        val adminMe = client.get("/api/users/me") { bearerAuth(admin) }.objectBody()
        val adminId = adminMe.getValue("id").jsonPrimitive.long
        val initialUsers = client.get("/api/admin/users?limit=1&offset=1") { bearerAuth(admin) }
        assertEquals(HttpStatusCode.OK, initialUsers.status)
        assertEquals(alice.id, initialUsers.arrayBody().single().jsonObject.getValue("id").jsonPrimitive.long)

        val promotion = client.patchJson("/api/admin/users/${alice.id}/role", role("admin"), admin)
        assertEquals(HttpStatusCode.OK, promotion.status)
        assertEquals("admin", promotion.objectBody().getValue("role").jsonPrimitive.content)
        assertEquals(HttpStatusCode.OK, client.get("/api/admin/users") { bearerAuth(alice.token) }.status)
        assertEquals(HttpStatusCode.OK,
            client.patchJson("/api/admin/users/${alice.id}/role", role("user"), admin).status)
        assertEquals(HttpStatusCode.Forbidden, client.get("/api/admin/users") { bearerAuth(alice.token) }.status)
        assertEquals(HttpStatusCode.Forbidden,
            client.patchJson("/api/admin/users/$adminId/role", role("user"), admin).status)
        assertEquals(HttpStatusCode.BadRequest,
            client.patchJson("/api/admin/users/${alice.id}/role", role("owner"), admin).status)
        assertEquals(HttpStatusCode.NotFound,
            client.patchJson("/api/admin/users/999999/role", role("user"), admin).status)
    }

    @Test
    fun `notifications are private and admin supports targeted and broadcast delivery`() = withApi { settings, _ ->
        val alice = register("alice")
        val bob = register("bob")
        val admin = adminToken(settings)
        val targeted = client.postJson("/api/admin/notifications", notification(alice.id), admin)
        assertEquals(HttpStatusCode.Created, targeted.status)
        assertEquals(1, targeted.objectBody().getValue("delivered").jsonPrimitive.int)
        val aliceNotifications = client.get("/api/notifications") { bearerAuth(alice.token) }.arrayBody()
        assertEquals(1, aliceNotifications.size)
        assertTrue(client.get("/api/notifications") { bearerAuth(bob.token) }.arrayBody().isEmpty())
        val notificationId = aliceNotifications.single().jsonObject.getValue("id").jsonPrimitive.long
        assertEquals(HttpStatusCode.NotFound,
            client.patch("/api/notifications/$notificationId/read") { bearerAuth(bob.token) }.status)
        val read = client.patch("/api/notifications/$notificationId/read") { bearerAuth(alice.token) }
        assertEquals(HttpStatusCode.OK, read.status)
        assertTrue(read.objectBody().getValue("read").jsonPrimitive.boolean)
        assertEquals(HttpStatusCode.NotFound,
            client.postJson("/api/admin/notifications", notification(999999), admin).status)
        assertEquals(HttpStatusCode.Forbidden,
            client.postJson("/api/admin/notifications", notification(bob.id), alice.token).status)

        val broadcast = client.postJson("/api/admin/notifications", notification(), admin)
        assertEquals(HttpStatusCode.Created, broadcast.status)
        assertEquals(3, broadcast.objectBody().getValue("delivered").jsonPrimitive.int)
        assertEquals(2, client.get("/api/notifications") { bearerAuth(alice.token) }.arrayBody().size)
        assertEquals(1, client.get("/api/notifications") { bearerAuth(bob.token) }.arrayBody().size)
        assertEquals(1, client.get("/api/notifications") { bearerAuth(admin) }.arrayBody().size)
    }

    @Test
    fun `chat persists messages and uses descending cursor pagination`() = withApi { _, _ ->
        val alice = register("alice")
        val bob = register("bob")
        val ids = mutableListOf<Long>()
        for ((index, account) in listOf(alice, bob, alice).withIndex()) {
            val response = client.postJson("/api/messages", buildJsonObject { put("text", "  Message $index  ") }, account.token)
            assertEquals(HttpStatusCode.Created, response.status)
            val saved = response.objectBody()
            assertEquals("Message $index", saved.getValue("text").jsonPrimitive.content)
            assertEquals(account.id, saved.getValue("userId").jsonPrimitive.long)
            ids += saved.getValue("id").jsonPrimitive.long
        }
        val latest = client.get("/api/messages?limit=2") { bearerAuth(bob.token) }
        assertEquals(HttpStatusCode.OK, latest.status)
        assertEquals(ids.reversed().take(2), latest.arrayBody().map { it.jsonObject.getValue("id").jsonPrimitive.long })
        val older = client.get("/api/messages?before=${ids[1]}&limit=2") { bearerAuth(alice.token) }.arrayBody()
        assertEquals(listOf(ids.first()), older.map { it.jsonObject.getValue("id").jsonPrimitive.long })
        assertEquals(HttpStatusCode.BadRequest,
            client.postJson("/api/messages", buildJsonObject { put("text", "  ") }, alice.token).status)
        assertEquals(HttpStatusCode.BadRequest,
            client.postJson("/api/messages", buildJsonObject { put("text", "x".repeat(2001)) }, alice.token).status)
        for (query in listOf("limit=0", "limit=201", "limit=x", "before=0", "before=x")) {
            assertEquals(HttpStatusCode.BadRequest,
                client.get("/api/messages?$query") { bearerAuth(alice.token) }.status, query)
        }
    }

    @Test
    fun `OpenAPI parses and describes auth secured routes and error responses`() {
        val text = checkNotNull(javaClass.getResource("/openapi/documentation.yaml")).readText()
        val parsed = OpenAPIV3Parser().readContents(text, null, null)
        assertTrue(parsed.messages.isNullOrEmpty(), parsed.messages.toString())
        val api = assertNotNull(parsed.openAPI)
        assertTrue(api.components.securitySchemes.values.any { it.scheme == "bearer" && it.bearerFormat == "JWT" })
        assertNotNull(api.paths["/api/auth/register"]?.post?.responses?.get("201"))
        assertNotNull(api.paths["/api/auth/login"]?.post?.responses?.get("401"))
        for (path in listOf("/api/users/me", "/api/messages", "/api/notifications", "/api/admin/users")) {
            val operation = assertNotNull(api.paths[path]?.get, path)
            assertTrue((operation.security ?: api.security).orEmpty().isNotEmpty(), "$path must require JWT")
            assertNotNull(operation.responses["200"], path)
            assertNotNull(operation.responses["401"], path)
        }
    }

    @Test
    fun `Swagger and health are served without authentication`() = withApi { _, _ ->
        assertEquals(HttpStatusCode.OK, client.get("/health/live").status)
        assertEquals(HttpStatusCode.OK, client.get("/health/ready").status)
        val swagger = client.get("/swagger")
        assertEquals(HttpStatusCode.OK, swagger.status)
        assertTrue(swagger.bodyAsText().contains("/openapi.yaml"))
        val spec = client.get("/openapi.yaml")
        assertEquals(HttpStatusCode.OK, spec.status)
        assertTrue(spec.bodyAsText().contains("openapi:"))
        assertEquals(HttpStatusCode.OK, client.get("/swagger-assets/swagger-ui-bundle.js").status)
    }

    private fun withApi(block: suspend ApplicationTestBuilder.(Settings, InMemoryStore) -> Unit) = testApplication {
        val settings = Settings(
            databaseUrl = "jdbc:unused",
            databaseUser = "test",
            databasePassword = "test",
            jwtSecret = "test-secret-".repeat(4),
            adminPassword = "test-admin-password",
        )
        val store = InMemoryStore()
        bootstrapAdmin(store, settings)
        application { module(settings, store) }
        block(settings, store)
    }

    private suspend fun ApplicationTestBuilder.register(username: String): Account {
        val response = client.postJson("/api/auth/register", credentials(username))
        assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
        val body = response.objectBody()
        return Account(body.getValue("accessToken").jsonPrimitive.content,
            body.getValue("user").jsonObject.getValue("id").jsonPrimitive.long)
    }

    private suspend fun ApplicationTestBuilder.adminToken(settings: Settings): String {
        val response = client.postJson("/api/auth/login", credentials(settings.adminUsername, settings.adminPassword))
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        return response.objectBody().getValue("accessToken").jsonPrimitive.content
    }

    private data class Account(val token: String, val id: Long)

    private fun credentials(username: String, password: String = TEST_PASSWORD) = buildJsonObject {
        put("username", username)
        put("password", password)
    }

    private fun role(value: String) = buildJsonObject { put("role", value) }

    private fun notification(userId: Long? = null) = buildJsonObject {
        put("title", "Test notification")
        put("message", "Only the intended users may read this")
        if (userId != null) put("userId", userId)
    }

    private suspend fun HttpClient.postJson(path: String, payload: JsonObject, token: String? = null): HttpResponse = post(path) {
        contentType(ContentType.Application.Json)
        if (token != null) bearerAuth(token)
        setBody(payload.toString())
    }

    private suspend fun HttpClient.patchJson(path: String, payload: JsonObject, token: String): HttpResponse = patch(path) {
        contentType(ContentType.Application.Json)
        bearerAuth(token)
        setBody(payload.toString())
    }

    private suspend fun HttpResponse.objectBody(): JsonObject = Json.parseToJsonElement(bodyAsText()).jsonObject
    private suspend fun HttpResponse.arrayBody(): JsonArray = Json.parseToJsonElement(bodyAsText()).jsonArray

    private companion object {
        const val TEST_PASSWORD = "user-test-password"
    }
}
