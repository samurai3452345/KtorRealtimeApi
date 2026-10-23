package ru.student.realtime

import kotlinx.serialization.Serializable

@Serializable
enum class Role { admin, user }

@Serializable
data class UserDto(
    val id: Long,
    val username: String,
    val role: Role,
    val createdAt: String,
)

data class UserRecord(
    val id: Long,
    val username: String,
    val passwordHash: String,
    val role: Role,
    val createdAt: String,
) {
    fun toDto(): UserDto = UserDto(id, username, role, createdAt)
}

@Serializable
data class RegisterRequest(val username: String, val password: String)

@Serializable
data class LoginRequest(val username: String, val password: String)

@Serializable
data class TokenResponse(
    val accessToken: String,
    val expiresIn: Long,
    val user: UserDto,
    val tokenType: String = "Bearer",
)

@Serializable
data class ChangeRoleRequest(val role: Role)

@Serializable
data class SendMessageRequest(val text: String)

@Serializable
data class ChatMessageDto(
    val id: Long,
    val userId: Long,
    val username: String,
    val text: String,
    val createdAt: String,
)

@Serializable
data class NotificationDto(
    val id: Long,
    val userId: Long,
    val title: String,
    val message: String,
    val read: Boolean,
    val createdAt: String,
)

@Serializable
data class CreateNotificationRequest(
    val title: String,
    val message: String,
    val userId: Long? = null,
)

@Serializable
data class WsEvent(
    val type: String,
    val message: ChatMessageDto? = null,
    val notification: NotificationDto? = null,
    val detail: String? = null,
)

@Serializable
data class ApiError(val error: String)

class StoreConflictException(
    message: String = "Пользователь с таким именем уже существует",
    cause: Throwable? = null,
) : RuntimeException(message, cause)

interface AppStore : AutoCloseable {
    suspend fun createUser(username: String, passwordHash: String, role: Role = Role.user): UserRecord
    suspend fun findUserByUsername(username: String): UserRecord?
    suspend fun findUserById(id: Long): UserRecord?
    suspend fun listUsers(limit: Int, offset: Int): List<UserDto>
    suspend fun updateRole(id: Long, role: Role): UserDto?
    suspend fun createMessage(userId: Long, text: String): ChatMessageDto
    suspend fun listMessages(before: Long?, limit: Int): List<ChatMessageDto>
    suspend fun createNotifications(targetUserId: Long?, title: String, message: String): List<NotificationDto>
    suspend fun listNotifications(userId: Long, limit: Int): List<NotificationDto>
    suspend fun markNotificationRead(userId: Long, id: Long): NotificationDto?
    suspend fun healthy(): Boolean
}
