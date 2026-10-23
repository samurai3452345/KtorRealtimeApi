package ru.student.realtime

import io.ktor.server.engine.*
import io.ktor.server.netty.*
import kotlinx.coroutines.runBlocking

fun main() {
    val settings = Settings.fromEnvironment()
    val database = Database(settings)
    try {
        database.initialize()
        val store = PostgresStore(database)
        runBlocking { bootstrapAdmin(store, settings) }
        embeddedServer(Netty, host = "0.0.0.0", port = settings.port) {
            module(settings, store)
        }.start(wait = true)
    } finally {
        database.close()
    }
}
