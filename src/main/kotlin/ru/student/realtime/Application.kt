package ru.student.realtime

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.http.content.*
import io.ktor.server.plugins.*
import io.ktor.server.plugins.calllogging.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.util.Locale
import kotlin.time.Duration.Companion.seconds

class ApiException(val status: HttpStatusCode, override val message: String) : RuntimeException(message)

private val ResponseHeaders = createApplicationPlugin("ResponseHeaders") {
    onCallRespond { call, _ ->
        call.response.headers.append("X-Content-Type-Options", "nosniff")
        call.response.headers.append("Referrer-Policy", "no-referrer")
        if (call.request.path().startsWith("/api/")) call.response.headers.append(HttpHeaders.CacheControl, "no-store")
    }
}

fun Application.module(settings: Settings, store: AppStore, hub: RealtimeHub = RealtimeHub()) {
    val tokens = TokenService(settings)
    // Тот же объём работы при ошибке логина для существующего и неизвестного пользователя.
    val dummyHash = runBlocking { Passwords.hash("invalid-login-placeholder") }
    install(ContentNegotiation) { json(Json { encodeDefaults = true; explicitNulls = false; ignoreUnknownKeys = false }) }
    install(CallLogging) { filter { it.request.path().startsWith("/api/") } }
    install(ResponseHeaders)
    install(WebSockets) { pingPeriod = 15.seconds; timeout = 15.seconds; maxFrameSize = 16 * 1024; masking = false }
    install(StatusPages) {
        exception<ApiException> { call, error -> call.respond(error.status, ApiError(error.message)) }
        exception<StoreConflictException> { call, error -> call.respond(HttpStatusCode.Conflict, ApiError(error.message ?: "Конфликт данных")) }
        exception<BadRequestException> { call, _ -> call.respond(HttpStatusCode.BadRequest, ApiError("Некорректный JSON или параметры запроса")) }
        exception<io.ktor.server.plugins.ContentTransformationException> { call, _ -> call.respond(HttpStatusCode.BadRequest, ApiError("Ожидается JSON правильного формата")) }
        exception<SerializationException> { call, _ -> call.respond(HttpStatusCode.BadRequest, ApiError("Некорректный JSON")) }
        exception<Throwable> { call, error ->
            if (error is kotlinx.coroutines.CancellationException) throw error
            this@module.log.error("Request processing failed", error)
            call.respond(HttpStatusCode.InternalServerError, ApiError("Внутренняя ошибка сервера"))
        }
        status(HttpStatusCode.NotFound) { call, status -> call.respond(status, ApiError("Ресурс не найден")) }
        status(HttpStatusCode.UnsupportedMediaType) { call, status -> call.respond(status, ApiError("Используйте Content-Type: application/json")) }
    }
    configureAuthentication(store, tokens)
    monitor.subscribe(ApplicationStopped) { runBlocking { hub.close() } }

    routing {
        get("/health/live") { call.respond(mapOf("status" to "ok")) }
        get("/health/ready") {
            val healthy = try { store.healthy() } catch (_: Exception) { false }
            call.respond(if (healthy) HttpStatusCode.OK else HttpStatusCode.ServiceUnavailable,
                mapOf("status" to if (healthy) "ok" else "unavailable"))
        }
        documentationRoutes()
        route("/api/auth") {
            post("/register") {
                val request = call.receive<RegisterRequest>()
                val username = validUsername(request.username)
                validPassword(request.password)
                val user = store.createUser(username, Passwords.hash(request.password), Role.user)
                call.respond(HttpStatusCode.Created, tokens.issue(user))
            }
            post("/login") {
                val request = call.receive<LoginRequest>()
                val username = validUsername(request.username)
                validPassword(request.password)
                val user = store.findUserByUsername(username)
                val correct = Passwords.verify(request.password, user?.passwordHash ?: dummyHash)
                if (user == null || !correct) throw ApiException(HttpStatusCode.Unauthorized, "Неверное имя пользователя или пароль")
                call.respond(tokens.issue(user))
            }
        }
        authenticate("jwt") {
            get("/api/users/me") { call.respond(call.currentUser().toDto()) }
            get("/api/messages") {
                val before = call.request.queryParameters["before"]?.let { positiveId(it, "before") }
                call.respond(store.listMessages(before, call.limit(50)))
            }
            post("/api/messages") {
                val text = validText(call.receive<SendMessageRequest>().text, "text", 2000)
                val message = store.createMessage(call.currentUser().id, text)
                hub.publishMessage(message)
                call.respond(HttpStatusCode.Created, message)
            }
            get("/api/notifications") {
                call.respond(store.listNotifications(call.currentUser().id, call.limit(100)))
            }
            patch("/api/notifications/{id}/read") {
                val result = store.markNotificationRead(call.currentUser().id, call.pathId())
                    ?: throw ApiException(HttpStatusCode.NotFound, "Уведомление не найдено")
                call.respond(result)
            }
            route("/api/admin") {
                get("/users") {
                    call.requireAdmin()
                    val offset = call.request.queryParameters["offset"]?.let {
                        it.toIntOrNull()?.takeIf { value -> value >= 0 }
                            ?: throw ApiException(HttpStatusCode.BadRequest, "offset должен быть целым числом от 0")
                    } ?: 0
                    call.respond(store.listUsers(call.limit(100), offset))
                }
                patch("/users/{id}/role") {
                    val admin = call.requireAdmin()
                    val id = call.pathId()
                    val request = call.receive<ChangeRoleRequest>()
                    if (id == admin.id && request.role != Role.admin)
                        throw ApiException(HttpStatusCode.Forbidden, "Администратор не может понизить собственную роль")
                    val user = store.updateRole(id, request.role)
                        ?: throw ApiException(HttpStatusCode.NotFound, "Пользователь не найден")
                    call.respond(user)
                }
                post("/notifications") {
                    call.requireAdmin()
                    val request = call.receive<CreateNotificationRequest>()
                    val title = validText(request.title, "title", 100)
                    val message = validText(request.message, "message", 2000)
                    if (request.userId != null && request.userId <= 0)
                        throw ApiException(HttpStatusCode.BadRequest, "userId должен быть положительным")
                    val notifications = store.createNotifications(request.userId, title, message)
                    if (request.userId != null && notifications.isEmpty())
                        throw ApiException(HttpStatusCode.NotFound, "Пользователь не найден")
                    notifications.forEach { hub.publishNotification(it) }
                    call.respond(HttpStatusCode.Created, mapOf("delivered" to notifications.size))
                }
            }
        }
        realtimeRoutes(store, tokens, hub)
        staticResources("/", "static")
    }
}

private fun ApplicationCall.currentUser(): UserRecord = principal<UserPrincipal>()?.user
    ?: throw ApiException(HttpStatusCode.Unauthorized, "Требуется авторизация")

private fun ApplicationCall.requireAdmin(): UserRecord = currentUser().also {
    if (it.role != Role.admin) throw ApiException(HttpStatusCode.Forbidden, "Доступ разрешён только администратору")
}

private fun validUsername(value: String): String = value.trim().lowercase(Locale.ROOT).also {
    if (!it.matches(Regex("[a-z0-9_]{3,32}")))
        throw ApiException(HttpStatusCode.BadRequest, "username: 3–32 латинские буквы, цифры или _")
}

private fun validPassword(value: String) {
    if (value.length !in 10..128) throw ApiException(HttpStatusCode.BadRequest, "password: от 10 до 128 символов")
}

private fun validText(value: String, field: String, max: Int): String = value.trim().also {
    if (it.isEmpty() || it.length > max) throw ApiException(HttpStatusCode.BadRequest, "$field: от 1 до $max символов")
}

private fun positiveId(value: String?, field: String): Long = value?.toLongOrNull()?.takeIf { it > 0 }
    ?: throw ApiException(HttpStatusCode.BadRequest, "$field должен быть положительным целым числом")

private fun ApplicationCall.pathId(): Long = positiveId(parameters["id"], "id")

private fun ApplicationCall.limit(default: Int): Int = request.queryParameters["limit"]?.let {
    it.toIntOrNull()?.takeIf { value -> value in 1..200 }
        ?: throw ApiException(HttpStatusCode.BadRequest, "limit должен быть от 1 до 200")
} ?: default
