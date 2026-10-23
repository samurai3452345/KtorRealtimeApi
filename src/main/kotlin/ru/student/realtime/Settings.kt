package ru.student.realtime

data class Settings(
    val databaseUrl: String,
    val databaseUser: String,
    val databasePassword: String,
    val jwtSecret: String,
    val adminUsername: String = "admin",
    val adminPassword: String,
    val issuer: String = "ktor-realtime-api",
    val audience: String = "ktor-realtime-client",
    val jwtTtlSeconds: Long = 3600,
    val port: Int = 8091
) {
    init {
        require(jwtSecret.length >= 32) { "JWT_SECRET must contain at least 32 characters" }
        require(databasePassword.isNotBlank()) { "DATABASE_PASSWORD is required" }
        require(adminUsername.matches(Regex("[a-z0-9_]{3,32}"))) { "Invalid ADMIN_USERNAME" }
        require(adminPassword.length in 10..128) { "ADMIN_PASSWORD must contain 10..128 characters" }
        require(jwtTtlSeconds in 60..86400) { "JWT_TTL_SECONDS must be between 60 and 86400" }
        require(port in 1..65535) { "Invalid PORT" }
    }

    companion object {
        fun fromEnvironment(): Settings {
            fun required(name: String): String = System.getenv(name)?.takeIf { it.isNotBlank() }
                ?: error("$name is required. Run scripts/Init-Env.ps1 and scripts/Run-Local.ps1, or docker compose up --build -d")
            return Settings(
                databaseUrl = System.getenv("DATABASE_URL") ?: "jdbc:postgresql://localhost:55435/ktor_realtime",
                databaseUser = System.getenv("DATABASE_USER") ?: "realtime_app",
                databasePassword = required("DATABASE_PASSWORD"),
                jwtSecret = required("JWT_SECRET"),
                adminUsername = System.getenv("ADMIN_USERNAME") ?: "admin",
                adminPassword = required("ADMIN_PASSWORD"),
                jwtTtlSeconds = (System.getenv("JWT_TTL_SECONDS") ?: "3600").toLong(),
                port = (System.getenv("PORT") ?: "8091").toInt()
            )
        }
    }
}
