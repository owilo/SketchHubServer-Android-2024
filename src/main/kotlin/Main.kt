package com.example.server

import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.sessions.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.channels.consumeEach
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.kotlin.datetime.CurrentDateTime
import org.jetbrains.exposed.sql.statements.api.ExposedBlob
import org.jetbrains.exposed.sql.transactions.transaction

@Serializable
data class UserSession(val username: String)

@Serializable
data class UserRegistration(
    val username: String,
    val email: String,
    val password: String,
    val surname: String?,
    val city: String?,
    val premium: Boolean
)

@Serializable
data class UserAuthentication(
    val username: String,
    val password: String
)

@Serializable
data class GalleryData(
    val canvasData: List<CanvasData>
)

@Serializable
data class CanvasData(
    val drawingId: Int,
    val title: String,
    val historyId: Int,
    val data: ByteArray
)

@Serializable
data class UpdateDrawingData (
    val drawingId: Int?,
    val title: String,
    val description: String,
    val visibility: Boolean
)

lateinit var sessionUsername: String

fun main() {
    try {
        embeddedServer(Netty, port = 8080) {
            install(Authentication) {
                basic("basic-auth") {
                    realm = "SketchHubServer"
                    println("Trying to log in...")
                    validate { credentials ->
                        println(credentials)
                        val user = transaction {
                            User.selectAll().where { User.username eq credentials.name }.singleOrNull()
                        }

                        if (user != null && hashPassword(credentials.password).contentEquals(user[User.password])) {
                            UserIdPrincipal(credentials.name)
                        } else {
                            null
                        }
                    }
                }
            }

            install(Sessions) {
                cookie<UserSession>("SESSION")
            }

            install(ContentNegotiation) {
                json()
            }

            install(WebSockets)

            Database.connect("jdbc:sqlite:src/main/resources/database.db", "org.sqlite.JDBC")

            transaction {
                SchemaUtils.create(User, Invitation, Subscription, Drawing, History, Collaboration)
            }

            println("Server running")

            routing {

                post("/register") {
                    val userRegistration = call.receive<UserRegistration>()

                    val response = transaction {
                        try {
                            User.insert {
                                it[username] = userRegistration.username
                                it[password] = hashPassword(userRegistration.password)
                                it[email] = userRegistration.email
                                it[surname] = userRegistration.surname ?: ""
                                it[city] = userRegistration.city ?: ""
                                it[description] = ""
                            }
                            sessionUsername = userRegistration.username
                            call.sessions.set(UserSession(userRegistration.username))
                            true
                        } catch (e: Exception) {
                            false
                        }
                    }

                    println("Received registration data $userRegistration with response $response")
                    call.respondText(response.toString(), ContentType.Application.Json)
                }

                post("/authenticate") {
                    val userAuthentication = call.receive<UserAuthentication>()

                    val user = transaction {
                        User.selectAll().where { User.username eq userAuthentication.username }.singleOrNull()
                    }

                    val response = if (user != null && hashPassword(userAuthentication.password).contentEquals(user[User.password])) {
                        UserIdPrincipal(userAuthentication.username)
                        sessionUsername = userAuthentication.username
                        call.sessions.set(UserSession(userAuthentication.username))
                        true
                    }
                    else {
                        false
                    }

                    println("Received authentication data $userAuthentication with response $response")
                    println(call.sessions)
                    call.respond(response)
                }

                webSocket("/draw") {
                    try {
                        incoming.consumeEach { frame ->
                            if (frame is Frame.Text) {
                                val receivedText = frame.readText()
                                println("Received: $receivedText")
                                send(Frame.Text("You said: $receivedText"))
                            }
                        }
                    } catch (e: ClosedReceiveChannelException) {
                        println("Client disconnected")
                    }
                }

                get("/getGallery") {
                    val session = call.sessions.get<UserSession>()
                    val sessionUsername = sessionUsername
                    println(call.sessions)
                    /*if (session == null) {
                        call.respond(HttpStatusCode.Unauthorized, "No session")
                        return@get
                    }*/

                    val histories = transaction {
                        val latestHistoryDates = History
                            .select(History.drawing_id, History.save_date.max())
                            .groupBy(History.drawing_id)
                            .alias("latest_histories")

                        val query = (Drawing innerJoin latestHistoryDates)
                            .slice(Drawing.id, latestHistoryDates[History.save_date])
                            .select {
                                (Drawing.owner_username eq sessionUsername) and
                                (Drawing.id eq latestHistoryDates[History.drawing_id]) and
                                (History.save_date eq latestHistoryDates[History.save_date])
                            }

                            query.map {
                                val drawingId = it[Drawing.id]
                                val drawingTitle = Drawing.select { Drawing.id eq drawingId }.singleOrNull()?.get(Drawing.title)
                                val historyId = it[latestHistoryDates[History.id]]
                                val historyData = History.select { History.id eq historyId }.singleOrNull()?.get(History.data)

                                CanvasData(drawingId, drawingTitle!!, historyId, historyData!!.bytes)
                            }

                    }

                    println("Received gallery request with response $histories")
                    call.respond(GalleryData(histories))
                }

                post("/createCanvas") {
                    val createDrawingData = call.receive<UpdateDrawingData>()
                    val session = call.sessions.get<UserSession>()
                    val sessionUsername = sessionUsername
                    /*if (session == null) {
                        call.respond(HttpStatusCode.Unauthorized, "No session")
                        return@post
                    }*/

                    val width = 512
                    val height = 512
                    val channels = 3
                    val totalSize = channels * width * height

                    val canvasData = transaction {
                        val drawing = Drawing.insert {
                            it[title] = createDrawingData.title
                            it[description] = createDrawingData.description
                            it[public] = createDrawingData.visibility
                            it[owner_username] = sessionUsername
                        }.resultedValues!!.first()

                        val history = History.insert {
                            it[save_date] = CurrentDateTime
                            it[data] = ExposedBlob(ByteArray(totalSize) { 0xFF.toByte() })
                            it[drawing_id] = drawing[Drawing.id]
                        }.resultedValues!!.first()

                        CanvasData(drawing[Drawing.id], drawing[Drawing.title], history[History.id], history[History.data].bytes)
                    }

                    println("Received canvas create data $createDrawingData with response $canvasData")
                    call.respond(canvasData)
                }

                post("/editCanvas") {
                    val updateDrawingData = call.receive<UpdateDrawingData>()
                    val session = call.sessions.get<UserSession>()
                    /*if (session == null) {
                        call.respond(HttpStatusCode.Unauthorized, "No session")
                        return@post
                    }*/

                    transaction {
                        Drawing.update({ Drawing.id eq updateDrawingData.drawingId!! }) {
                            it[title] = updateDrawingData.title
                            it[description] = updateDrawingData.description
                            it[public] = updateDrawingData.visibility
                        }
                    }

                    println("Received canvas update data $updateDrawingData")
                }
            }
        }.start(wait = true)
    }
    catch (e : Exception) {
        e.printStackTrace()
    }
}