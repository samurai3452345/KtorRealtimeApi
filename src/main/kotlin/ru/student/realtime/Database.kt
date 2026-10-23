package ru.student.realtime

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.sql.Connection

class Database(settings: Settings) : AutoCloseable {
    private val dataSource = HikariDataSource(HikariConfig().apply {
        jdbcUrl = settings.databaseUrl
        username = settings.databaseUser
        password = settings.databasePassword
        maximumPoolSize = 10
        minimumIdle = 1
        connectionTimeout = 10_000
        validationTimeout = 3_000
        initializationFailTimeout = 10_000
        poolName = "realtime-postgres"
    })

    fun initialize() {
        val schema = requireNotNull(javaClass.classLoader.getResourceAsStream("db/schema.sql")) {
            "Ресурс db/schema.sql не найден"
        }.bufferedReader(Charsets.UTF_8).use { it.readText() }

        dataSource.connection.use { connection ->
            connection.inTransaction {
                connection.createStatement().use { statement ->
                    schema.split(';').map(String::trim).filter(String::isNotBlank).forEach(statement::execute)
                }
            }
        }
    }

    suspend fun <T> query(block: (Connection) -> T): T = withContext(Dispatchers.IO) {
        dataSource.connection.use(block)
    }

    override fun close() = dataSource.close()
}

internal fun <T> Connection.inTransaction(block: () -> T): T {
    val previousAutoCommit = autoCommit
    autoCommit = false
    try {
        val result = block()
        commit()
        return result
    } catch (failure: Throwable) {
        try {
            rollback()
        } catch (rollbackFailure: Throwable) {
            failure.addSuppressed(rollbackFailure)
        }
        throw failure
    } finally {
        autoCommit = previousAutoCommit
    }
}
