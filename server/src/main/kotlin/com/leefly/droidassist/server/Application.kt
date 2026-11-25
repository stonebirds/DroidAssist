package com.leefly.droidassist.server

import com.leefly.droidassist.protocol.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.time.Duration.Companion.seconds

private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

fun main() {
    val host = System.getenv("DROIDASSIST_HOST") ?: "127.0.0.1"
    val port = System.getenv("DROIDASSIST_PORT")?.toIntOrNull() ?: 8080
    val databasePath = Path.of(System.getenv("DROIDASSIST_DB") ?: "data/droidassist.db")
    val database = Database(databasePath)
    Runtime.getRuntime().addShutdownHook(Thread(database::close))
    embeddedServer(CIO, host = host, port = port) { module(database) }.start(wait = true)
}

internal fun Application.module(database: Database) {
    val logger = environment.log
    install(ContentNegotiation) { json(json) }
    install(WebSockets) {
        pingPeriod = 20.seconds
        timeout = 20.seconds
        maxFrameSize = 1024 * 1024
        masking = false
    }
    install(StatusPages) {
        exception<Throwable> { call, cause ->
            logger.error("Request failed", cause)
            call.respond(HttpStatusCode.InternalServerError, ApiError("internal_error", "Internal server error"))
        }
    }

    val sockets = ConcurrentHashMap<String, DefaultWebSocketServerSession>()
    val pendingSignals = ConcurrentHashMap<String, ConcurrentLinkedQueue<String>>()
    fun queueSignal(deviceId: String, encoded: String) {
        val queue = pendingSignals.computeIfAbsent(deviceId) { ConcurrentLinkedQueue() }
        while (queue.size >= 256) queue.poll()
        queue.offer(encoded)
        logger.info("Queued signal for offline device {}", deviceId.take(8))
    }
    suspend fun deliverSignal(deviceId: String, encoded: String, queueIfOffline: Boolean = true) {
        val socket = sockets[deviceId]
        if (socket == null || runCatching { socket.send(Frame.Text(encoded)) }.isFailure) {
            if (queueIfOffline) queueSignal(deviceId, encoded)
        }
    }
    suspend fun sendTo(deviceId: String, sessionId: String, type: SignalType, payload: JsonElement) {
        val signal = SignalEnvelope(sessionId, "server", deviceId, type, payload)
        deliverSignal(
            deviceId,
            json.encodeToString(SignalEnvelope.serializer(), signal),
            queueIfOffline = type != SignalType.SESSION_REQUEST,
        )
    }
    routing {
        get("/health") { call.respond(HealthResponse("ok", System.currentTimeMillis())) }

        route("/api/v1") {
            post("/devices/register") {
                call.respond(HttpStatusCode.Created, database.register(call.receive()))
            }
            post("/grants") {
                val device = call.requireDevice(database) ?: return@post
                call.respond(HttpStatusCode.Created, database.createGrant(device, call.receive()))
            }
            post("/sessions/connect") {
                val device = call.requireDevice(database) ?: return@post
                val request = call.receive<ConnectRequest>()
                if (request.controllerDeviceId != device.id) {
                    call.respond(HttpStatusCode.Forbidden, ApiError("device_mismatch", "Controller identity does not match token"))
                    return@post
                }
                val response = database.connect(request)
                if (response == null) {
                    call.respond(HttpStatusCode.Unauthorized, ApiError("invalid_grant", "Code or password is invalid, expired, or locked"))
                } else {
                    database.pendingSession(response.controlledDeviceId)?.let { pending ->
                        sendTo(
                            response.controlledDeviceId,
                            response.sessionId,
                            SignalType.SESSION_REQUEST,
                            json.encodeToJsonElement(IncomingSessionResponse.serializer(), pending),
                        )
                    }
                    call.respond(HttpStatusCode.Created, response)
                }
            }
            post("/sessions/{id}/confirm") {
                val device = call.requireDevice(database) ?: return@post
                val sessionId = call.parameters["id"] ?: return@post call.respond(HttpStatusCode.BadRequest)
                val response = database.confirm(device.id, sessionId, call.receive())
                if (response == null) {
                    call.respond(HttpStatusCode.NotFound, ApiError("session_not_found", "Pending session not found"))
                } else {
                    sendTo(
                        response.peerDeviceId,
                        response.sessionId,
                        SignalType.SESSION_STATUS,
                        json.encodeToJsonElement(SessionStatusResponse.serializer(), response),
                    )
                    call.respond(response)
                }
            }
            get("/sessions/{id}") {
                val device = call.requireDevice(database) ?: return@get
                val sessionId = call.parameters["id"] ?: return@get call.respond(HttpStatusCode.BadRequest)
                val response = database.status(device.id, sessionId)
                if (response == null) call.respond(HttpStatusCode.NotFound, ApiError("session_not_found", "Session not found"))
                else call.respond(response)
            }
            get("/management") {
                val device = call.requireDevice(database) ?: return@get
                call.respond(database.management(device.id))
            }
            delete("/grants/{peerId}") {
                val device = call.requireDevice(database) ?: return@delete
                val peerId = call.parameters["peerId"] ?: return@delete call.respond(HttpStatusCode.BadRequest)
                if (database.revoke(device.id, peerId)) call.respond(HttpStatusCode.NoContent)
                else call.respond(HttpStatusCode.NotFound, ApiError("grant_not_found", "Authorization not found"))
            }
        }

        webSocket("/signal/{deviceId}") {
            val deviceId = call.parameters["deviceId"] ?: return@webSocket close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "Missing device"))
            val device = database.authenticate(call.request.queryParameters["token"])
            if (device?.id != deviceId) return@webSocket close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Unauthorized"))
            sockets.put(deviceId, this)?.close(CloseReason(CloseReason.Codes.NORMAL, "Reconnected"))
            database.setOnline(deviceId, true)
            try {
                pendingSignals.remove(deviceId)?.let { queue ->
                    while (true) {
                        val encoded = queue.poll() ?: break
                        send(Frame.Text(encoded))
                    }
                    logger.info("Delivered queued signals to device {}", deviceId.take(8))
                }
                database.pendingSession(deviceId)?.let { pending ->
                    sendTo(
                        deviceId,
                        pending.sessionId,
                        SignalType.SESSION_REQUEST,
                        json.encodeToJsonElement(IncomingSessionResponse.serializer(), pending),
                    )
                }
                for (frame in incoming) {
                    if (frame !is Frame.Text) continue
                    val signal = runCatching { json.decodeFromString<SignalEnvelope>(frame.readText()) }.getOrNull() ?: continue
                    if (signal.fromDeviceId != deviceId) continue
                    deliverSignal(signal.toDeviceId, json.encodeToString(SignalEnvelope.serializer(), signal))
                }
            } finally {
                sockets.remove(deviceId, this)
                database.setOnline(deviceId, false)
            }
        }
    }
}

private suspend fun ApplicationCall.requireDevice(database: Database): AuthenticatedDevice? {
    val token = request.headers[HttpHeaders.Authorization]?.removePrefix("Bearer ")
    val device = database.authenticate(token)
    if (device == null) respond(HttpStatusCode.Unauthorized, ApiError("unauthorized", "A valid device token is required"))
    return device
}
