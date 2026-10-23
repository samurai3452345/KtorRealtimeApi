package ru.student.realtime

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean

/** Isolated state for HTTP and WebSocket tests; every mutation is protected by one lock. */
class InMemoryStore : AppStore {
    private val mutex = Mutex()
    private val users = linkedMapOf<Long, UserRecord>()
    private val messages = linkedMapOf<Long, ChatMessageDto>()
    private val notifications = linkedMapOf<Long, NotificationDto>()
    private var nextUser = 1L
    private var nextMessage = 1L
    private var nextNotification = 1L
    private val closed = AtomicBoolean(false)

    override suspend fun createUser(username: String, passwordHash: String, role: Role): UserRecord = mutex.withLock {
        if (users.values.any { it.username.equals(username, ignoreCase = true) }) throw StoreConflictException()
        UserRecord(nextUser++, username, passwordHash, role, now()).also { users[it.id] = it }
    }

    override suspend fun findUserByUsername(username: String): UserRecord? = mutex.withLock {
        users.values.find { it.username.equals(username, ignoreCase = true) }
    }

    override suspend fun findUserById(id: Long): UserRecord? = mutex.withLock { users[id] }

    override suspend fun listUsers(limit: Int, offset: Int): List<UserDto> = mutex.withLock {
        users.values.drop(offset).take(limit).map(UserRecord::toDto)
    }

    override suspend fun updateRole(id: Long, role: Role): UserDto? = mutex.withLock {
        users[id]?.copy(role = role)?.also { users[id] = it }?.toDto()
    }

    override suspend fun createMessage(userId: Long, text: String): ChatMessageDto = mutex.withLock {
        val user = requireNotNull(users[userId])
        ChatMessageDto(nextMessage++, userId, user.username, text, now()).also { messages[it.id] = it }
    }

    override suspend fun listMessages(before: Long?, limit: Int): List<ChatMessageDto> = mutex.withLock {
        messages.values.reversed().filter { before == null || it.id < before }.take(limit)
    }

    override suspend fun createNotifications(
        targetUserId: Long?,
        title: String,
        message: String,
    ): List<NotificationDto> = mutex.withLock {
        users.values.filter { targetUserId == null || it.id == targetUserId }.map { user ->
            NotificationDto(nextNotification++, user.id, title, message, false, now())
                .also { notifications[it.id] = it }
        }
    }

    override suspend fun listNotifications(userId: Long, limit: Int): List<NotificationDto> = mutex.withLock {
        notifications.values.reversed().filter { it.userId == userId }.take(limit)
    }

    override suspend fun markNotificationRead(userId: Long, id: Long): NotificationDto? = mutex.withLock {
        notifications[id]?.takeIf { it.userId == userId }?.copy(read = true)
            ?.also { notifications[id] = it }
    }

    override suspend fun healthy(): Boolean = !closed.get()

    override fun close() { closed.set(true) }

    private fun now(): String = Instant.now().toString()
}
