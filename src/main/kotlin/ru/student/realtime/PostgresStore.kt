package ru.student.realtime

import kotlinx.coroutines.CancellationException
import java.sql.ResultSet
import java.sql.SQLException

class PostgresStore(private val database: Database) : AppStore {
    override suspend fun createUser(username: String, passwordHash: String, role: Role): UserRecord =
        database.query { connection ->
            try {
                connection.prepareStatement(
                    "INSERT INTO users (username, password_hash, role) VALUES (?, ?, ?) RETURNING *",
                ).use { statement ->
                    statement.setString(1, username)
                    statement.setString(2, passwordHash)
                    statement.setString(3, role.name)
                    statement.executeQuery().use { result ->
                        check(result.next())
                        result.userRecord()
                    }
                }
            } catch (exception: SQLException) {
                if (exception.sqlState == "23505") throw StoreConflictException(cause = exception)
                throw exception
            }
        }

    override suspend fun findUserByUsername(username: String): UserRecord? = database.query { connection ->
        connection.prepareStatement("SELECT * FROM users WHERE lower(username) = lower(?)").use { statement ->
            statement.setString(1, username)
            statement.executeQuery().use { result -> if (result.next()) result.userRecord() else null }
        }
    }

    override suspend fun findUserById(id: Long): UserRecord? = database.query { connection ->
        connection.prepareStatement("SELECT * FROM users WHERE id = ?").use { statement ->
            statement.setLong(1, id)
            statement.executeQuery().use { result -> if (result.next()) result.userRecord() else null }
        }
    }

    override suspend fun listUsers(limit: Int, offset: Int): List<UserDto> = database.query { connection ->
        connection.prepareStatement(
            "SELECT id, username, role, created_at FROM users ORDER BY id LIMIT ? OFFSET ?",
        ).use { statement ->
            statement.setInt(1, limit)
            statement.setInt(2, offset)
            statement.executeQuery().use { result -> buildList { while (result.next()) add(result.userDto()) } }
        }
    }

    override suspend fun updateRole(id: Long, role: Role): UserDto? = database.query { connection ->
        connection.prepareStatement(
            "UPDATE users SET role = ? WHERE id = ? RETURNING id, username, role, created_at",
        ).use { statement ->
            statement.setString(1, role.name)
            statement.setLong(2, id)
            statement.executeQuery().use { result -> if (result.next()) result.userDto() else null }
        }
    }

    override suspend fun createMessage(userId: Long, text: String): ChatMessageDto = database.query { connection ->
        connection.prepareStatement(
            """
            WITH inserted AS (
                INSERT INTO chat_messages (user_id, text) VALUES (?, ?) RETURNING *
            )
            SELECT inserted.*, users.username FROM inserted JOIN users ON users.id = inserted.user_id
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, userId)
            statement.setString(2, text)
            statement.executeQuery().use { result ->
                check(result.next())
                result.chatMessage()
            }
        }
    }

    override suspend fun listMessages(before: Long?, limit: Int): List<ChatMessageDto> = database.query { connection ->
        val where = if (before == null) "" else "WHERE chat_messages.id < ?"
        connection.prepareStatement(
            """
            SELECT chat_messages.*, users.username FROM chat_messages
            JOIN users ON users.id = chat_messages.user_id
            $where ORDER BY chat_messages.id DESC LIMIT ?
            """.trimIndent(),
        ).use { statement ->
            var parameter = 1
            if (before != null) statement.setLong(parameter++, before)
            statement.setInt(parameter, limit)
            statement.executeQuery().use { result -> buildList { while (result.next()) add(result.chatMessage()) } }
        }
    }

    override suspend fun createNotifications(
        targetUserId: Long?,
        title: String,
        message: String,
    ): List<NotificationDto> = database.query { connection ->
        connection.inTransaction {
            val where = if (targetUserId == null) "" else "WHERE id = ?"
            connection.prepareStatement(
                """
                INSERT INTO notifications (user_id, title, message)
                SELECT id, ?, ? FROM users $where ORDER BY id
                RETURNING *
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, title)
                statement.setString(2, message)
                if (targetUserId != null) statement.setLong(3, targetUserId)
                statement.executeQuery().use { result -> buildList { while (result.next()) add(result.notification()) } }
            }
        }
    }

    override suspend fun listNotifications(userId: Long, limit: Int): List<NotificationDto> = database.query { connection ->
        connection.prepareStatement(
            "SELECT * FROM notifications WHERE user_id = ? ORDER BY id DESC LIMIT ?",
        ).use { statement ->
            statement.setLong(1, userId)
            statement.setInt(2, limit)
            statement.executeQuery().use { result -> buildList { while (result.next()) add(result.notification()) } }
        }
    }

    override suspend fun markNotificationRead(userId: Long, id: Long): NotificationDto? = database.query { connection ->
        connection.prepareStatement(
            "UPDATE notifications SET is_read = TRUE WHERE user_id = ? AND id = ? RETURNING *",
        ).use { statement ->
            statement.setLong(1, userId)
            statement.setLong(2, id)
            statement.executeQuery().use { result -> if (result.next()) result.notification() else null }
        }
    }

    override suspend fun healthy(): Boolean = try {
        database.query { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT 1").use { result -> result.next() && result.getInt(1) == 1 }
            }
        }
    } catch (exception: CancellationException) {
        throw exception
    } catch (_: Exception) {
        false
    }

    override fun close() = database.close()
}

private fun ResultSet.userRecord() = UserRecord(
    id = getLong("id"),
    username = getString("username"),
    passwordHash = getString("password_hash"),
    role = Role.valueOf(getString("role")),
    createdAt = getTimestamp("created_at").toInstant().toString(),
)

private fun ResultSet.userDto() = UserDto(
    id = getLong("id"),
    username = getString("username"),
    role = Role.valueOf(getString("role")),
    createdAt = getTimestamp("created_at").toInstant().toString(),
)

private fun ResultSet.chatMessage() = ChatMessageDto(
    id = getLong("id"),
    userId = getLong("user_id"),
    username = getString("username"),
    text = getString("text"),
    createdAt = getTimestamp("created_at").toInstant().toString(),
)

private fun ResultSet.notification() = NotificationDto(
    id = getLong("id"),
    userId = getLong("user_id"),
    title = getString("title"),
    message = getString("message"),
    read = getBoolean("is_read"),
    createdAt = getTimestamp("created_at").toInstant().toString(),
)
