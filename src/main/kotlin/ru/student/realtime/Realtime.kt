package ru.student.realtime

import io.ktor.server.routing.Route
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

private val socketJson = Json { ignoreUnknownKeys = true }

@Serializable
private data class ClientSocketFrame(val type: String, val token: String? = null, val text: String? = null)

/** Bounded queues keep a slow browser from delaying other connected users. */
class RealtimeHub {
    internal class Connection(
        val id: UUID,
        val userId: Long,
        val session: DefaultWebSocketServerSession,
        val events: Channel<WsEvent> = Channel(64),
    )

    private val clients = ConcurrentHashMap<UUID, Connection>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    internal fun register(userId: Long, session: DefaultWebSocketServerSession): Connection =
        Connection(UUID.randomUUID(), userId, session).also { clients[it.id] = it }

    internal fun unregister(connection: Connection) {
        clients.remove(connection.id, connection)
        connection.events.close()
    }

    internal fun offer(connection: Connection, event: WsEvent) {
        if (connection.events.trySend(event).isFailure) {
            unregister(connection)
            scope.launch {
                runCatching {
                    connection.session.close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Client is too slow"))
                }
            }
        }
    }

    suspend fun publishMessage(message: ChatMessageDto) {
        val event = WsEvent(type = "message", message = message)
        clients.values.forEach { offer(it, event) }
    }

    suspend fun publishNotification(notification: NotificationDto) {
        val event = WsEvent(type = "notification", notification = notification)
        clients.values.filter { it.userId == notification.userId }.forEach { offer(it, event) }
    }

    suspend fun close() {
        val currentClients = clients.values.toList()
        currentClients.forEach(::unregister)
        currentClients.forEach { connection ->
            withTimeoutOrNull(1_000) {
                runCatching {
                    connection.session.close(CloseReason(CloseReason.Codes.GOING_AWAY, "Server is stopping"))
                }
            }
        }
        scope.cancel()
    }
}

fun Route.realtimeRoutes(store: AppStore, tokens: TokenService, hub: RealtimeHub) {
    webSocket("/ws") {
        // Tokens never appear in URLs, browser history or reverse-proxy access logs.
        val initialFrame = withTimeoutOrNull(5_000) { incoming.receiveCatching().getOrNull() }
        val auth = (initialFrame as? Frame.Text)?.let {
            runCatching { socketJson.decodeFromString<ClientSocketFrame>(it.readText()) }.getOrNull()
        }
        if (auth?.type != "auth" || auth.token.isNullOrBlank()) {
            close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Authentication required"))
            return@webSocket
        }
        val decoded = tokens.verify(auth.token)
        val expiresAt = decoded?.expiresAt?.time
        val user = tokens.authenticate(auth.token, store)
        if (user == null || expiresAt == null || expiresAt <= System.currentTimeMillis()) {
            close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Invalid or expired token"))
            return@webSocket
        }

        val connection = hub.register(user.id, this)
        coroutineScope {
            val sender = launch {
                try {
                    for (event in connection.events) send(Frame.Text(socketJson.encodeToString(event)))
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    runCatching { close(CloseReason(CloseReason.Codes.INTERNAL_ERROR, "Connection interrupted")) }
                }
            }
            val expiry = launch {
                delay((expiresAt - System.currentTimeMillis()).coerceAtLeast(0))
                close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Token expired"))
            }
            hub.offer(connection, WsEvent(type = "ready", detail = "Connected"))
            try {
                for (frame in incoming) {
                    if (frame !is Frame.Text) {
                        if (frame is Frame.Binary) {
                            close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "Text frames required"))
                            break
                        }
                        continue
                    }
                    val command = runCatching {
                        socketJson.decodeFromString<ClientSocketFrame>(frame.readText())
                    }.getOrNull()
                    if (command?.type != "message") {
                        hub.offer(connection, WsEvent(type = "error", detail = "Expected a message command"))
                        continue
                    }
                    val text = command.text?.trim()
                    if (text.isNullOrBlank() || text.length > 2_000) {
                        hub.offer(connection, WsEvent(type = "error", detail = "Message must contain 1 to 2000 characters"))
                        continue
                    }
                    if (expiresAt <= System.currentTimeMillis()) {
                        close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Token expired"))
                        break
                    }
                    try {
                        val message = store.createMessage(user.id, text)
                        hub.publishMessage(message)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        hub.offer(connection, WsEvent(type = "error", detail = "Unable to save message"))
                    }
                }
            } finally {
                hub.unregister(connection)
                sender.cancelAndJoin()
                expiry.cancelAndJoin()
            }
        }
    }
}
