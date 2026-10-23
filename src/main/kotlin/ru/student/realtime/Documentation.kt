package ru.student.realtime

import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Route.documentationRoutes() {
    get("/openapi.yaml") {
        call.respondText(checkNotNull(javaClass.getResource("/openapi/documentation.yaml")).readText(), ContentType.parse("application/yaml"))
    }
    get("/swagger") {
        call.respondText("""
            <!doctype html><html lang="ru"><head><meta charset="UTF-8"><title>КТ 4 · Swagger UI</title>
            <meta name="viewport" content="width=device-width,initial-scale=1">
            <link rel="stylesheet" href="/swagger-assets/swagger-ui.css"></head>
            <body><div id="swagger-ui"></div><script src="/swagger-assets/swagger-ui-bundle.js"></script>
            <script>SwaggerUIBundle({url:'/openapi.yaml',dom_id:'#swagger-ui',deepLinking:true,persistAuthorization:false});</script></body></html>
        """.trimIndent(), ContentType.Text.Html)
    }
    val assets = mapOf("swagger-ui.css" to ContentType.Text.CSS,
        "swagger-ui-bundle.js" to ContentType.Application.JavaScript)
    get("/swagger-assets/{asset}") {
        val name = call.parameters["asset"]
        val contentType = assets[name]
        if (contentType == null) call.respond(HttpStatusCode.NotFound)
        else {
            val bytes = checkNotNull(javaClass.getResourceAsStream("/META-INF/resources/webjars/swagger-ui/5.31.0/$name"))
                .use { it.readBytes() }
            call.respondBytes(bytes, contentType)
        }
    }
}
