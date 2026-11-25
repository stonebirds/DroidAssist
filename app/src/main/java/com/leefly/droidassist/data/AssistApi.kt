package com.leefly.droidassist.data

import com.leefly.droidassist.DiagnosticLog
import com.leefly.droidassist.protocol.*
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.websocket.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.websocket.*
import kotlinx.serialization.json.Json
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

class AssistApi : AutoCloseable {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val client = HttpClient(CIO) {
        install(ContentNegotiation) { json(json) }
        install(WebSockets)
        expectSuccess = true
    }

    suspend fun register(
        baseUrl: String, request: DeviceRegistrationRequest
    ): DeviceRegistrationResponse = client.post("${baseUrl.clean()}/api/v1/devices/register") {
        contentType(ContentType.Application.Json); setBody(
        request
    )
    }.body()

    suspend fun createGrant(baseUrl: String, token: String, type: GrantType): CreateGrantResponse =
        client.post("${baseUrl.clean()}/api/v1/grants") {
            bearerAuth(token); contentType(ContentType.Application.Json); setBody(
            CreateGrantRequest(
                type
            )
        )
        }.body()

    suspend fun connect(baseUrl: String, token: String, request: ConnectRequest): ConnectResponse =
        client.post("${baseUrl.clean()}/api/v1/sessions/connect") {
            bearerAuth(token); contentType(ContentType.Application.Json); setBody(request)
        }.body()

    suspend fun confirmSession(
        baseUrl: String,
        token: String,
        sessionId: String,
        approved: Boolean,
        trustController: Boolean,
    ): SessionStatusResponse =
        client.post("${baseUrl.clean()}/api/v1/sessions/$sessionId/confirm") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(ConfirmSessionRequest(approved, trustController))
        }.body()

    suspend fun listenSignals(
        baseUrl: String,
        deviceId: String,
        token: String,
        outgoing: ReceiveChannel<SignalEnvelope>,
        onSignal: suspend (SignalEnvelope) -> Unit,
    ) {
        val base = Url(baseUrl.clean())
        val signalUrl = URLBuilder(base).apply {
            protocol = if (base.protocol.name == "https") URLProtocol.WSS else URLProtocol.WS
            encodedPath = "/signal/$deviceId"
            parameters.append("token", token)
        }.buildString()
        client.webSocket(urlString = signalUrl) {
            DiagnosticLog.info("Signal", "Device WebSocket connected")
            coroutineScope {
                val sender = launch {
                    for (signal in outgoing) {
                        send(Frame.Text(json.encodeToString(SignalEnvelope.serializer(), signal)))
                    }
                }
                try {
                    for (frame in incoming) {
                        if (frame is Frame.Text) {
                            runCatching { json.decodeFromString<SignalEnvelope>(frame.readText()) }.getOrNull()
                                ?.let { onSignal(it) }
                        }
                    }
                } finally {
                    DiagnosticLog.warn("Signal", "Device WebSocket closed")
                    sender.cancel()
                }
            }
        }
    }

    suspend fun management(baseUrl: String, token: String): ManagementResponse =
        client.get("${baseUrl.clean()}/api/v1/management") { bearerAuth(token) }.body()

    suspend fun revoke(baseUrl: String, token: String, peerId: String) {
        client.delete("${baseUrl.clean()}/api/v1/grants/$peerId") { bearerAuth(token) }
    }

    private fun String.clean() = trim().trimEnd('/')
    override fun close() = client.close()
}
