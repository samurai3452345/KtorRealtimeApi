package ru.student.realtime

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.sql.SQLException
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Uses an explicitly configured test database and removes only records created by this test. */
class PostgresStoreTest {
    @Test
    fun `PostgreSQL enforces constraints isolation pagination and persistence after pool restart`() = runBlocking {
        val url = System.getenv("TEST_DATABASE_URL")
        assumeTrue(!url.isNullOrBlank(), "Set TEST_DATABASE_URL to enable PostgreSQL integration tests")
        val settings = Settings(
            databaseUrl = checkNotNull(url),
            databaseUser = System.getenv("TEST_DATABASE_USER") ?: "realtime_app",
            databasePassword = System.getenv("TEST_DATABASE_PASSWORD") ?: "test",
            jwtSecret = "integration-test-secret-".repeat(2),
            adminPassword = "integration-admin-password",
        )
        val prefix = "it_" + UUID.randomUUID().toString().replace("-", "").take(16)
        val userIds = mutableListOf<Long>()
        var store: PostgresStore? = null
        var database: Database? = null
        try {
            val initialDatabase = Database(settings).also { database = it; it.initialize() }
            val initialStore = PostgresStore(initialDatabase).also { store = it }
            assertTrue(initialStore.healthy())
            val alice = initialStore.createUser("${prefix}_alice", "test-hash", Role.user).also { userIds += it.id }
            val bob = initialStore.createUser("${prefix}_bob", "test-hash", Role.user).also { userIds += it.id }
            assertFailsWith<StoreConflictException> { initialStore.createUser(alice.username, "another-hash") }
            assertEquals(alice.id, initialStore.findUserByUsername(alice.username.uppercase())?.id)
            assertEquals(Role.admin, initialStore.updateRole(bob.id, Role.admin)?.role)
            assertEquals(Role.admin, initialStore.findUserById(bob.id)?.role)

            val first = initialStore.createMessage(alice.id, "First integration message")
            val second = initialStore.createMessage(bob.id, "Second integration message")
            val newest = initialStore.listMessages(before = null, limit = 200)
                .filter { it.userId in userIds }
            assertEquals(listOf(second.id, first.id), newest.map { it.id })
            assertEquals(first.id, initialStore.listMessages(before = second.id, limit = 1).single().id)
            assertFailsWith<SQLException> { initialStore.createMessage(alice.id, "x".repeat(2001)) }

            val notification = initialStore.createNotifications(alice.id, "Private", "For Alice").single()
            assertFalse(notification.read)
            assertTrue(initialStore.listNotifications(bob.id, 10).isEmpty())
            assertNull(initialStore.markNotificationRead(bob.id, notification.id))
            assertTrue(assertNotNull(initialStore.markNotificationRead(alice.id, notification.id)).read)
            assertTrue(initialStore.createNotifications(Long.MAX_VALUE, "Missing", "No recipient").isEmpty())
            assertFailsWith<SQLException> { initialStore.createNotifications(alice.id, "", "Invalid title") }

            initialStore.close()
            val restartedDatabase = Database(settings).also { database = it; it.initialize() }
            val restartedStore = PostgresStore(restartedDatabase).also { store = it }
            assertEquals(alice.toDto(), restartedStore.findUserById(alice.id)?.toDto())
            assertEquals(first.id, restartedStore.listMessages(second.id, 1).single().id)
            assertTrue(restartedStore.listNotifications(alice.id, 10).single().read)
        } finally {
            database?.let { activeDatabase ->
                try {
                    activeDatabase.query { connection ->
                        connection.inTransaction {
                            for (id in userIds) {
                                connection.prepareStatement("DELETE FROM chat_messages WHERE user_id = ?").use {
                                    it.setLong(1, id)
                                    it.executeUpdate()
                                }
                                connection.prepareStatement("DELETE FROM users WHERE id = ?").use {
                                    it.setLong(1, id)
                                    it.executeUpdate()
                                }
                            }
                        }
                    }
                } finally {
                    store?.close()
                }
            }
        }
    }
}
