package ru.student.realtime

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.*

class SecurityTest {
    @Test
    fun `password hashes use distinct salts and reject incorrect passwords`() = runTest {
        val password = "a-strong-test-password"
        val first = Passwords.hash(password)
        val second = Passwords.hash(password)
        assertNotEquals(first, second)
        assertFalse(first.contains(password))
        assertTrue(Passwords.verify(password, first))
        assertFalse(Passwords.verify("wrong-password", first))
        assertFalse(Passwords.verify(password, "malformed-hash"))
    }
}
