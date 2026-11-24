package com.leefly.droidassist.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class ApiError(val code: String, val message: String)

@Serializable
data class HealthResponse(val status: String, val epochMillis: Long)

@Serializable
data class DeviceRegistrationRequest(
    val installationId: String,
    val displayName: String,
    val publicKey: String,
    val platform: String = "android",
)

@Serializable
data class DeviceRegistrationResponse(
    val deviceId: String,
    val matchingCode: String,
    val deviceToken: String,
)

@Serializable
enum class GrantType { TEMPORARY, PERMANENT }

@Serializable
data class CreateGrantRequest(
    val type: GrantType,
    val ttlSeconds: Long = 600,
)

@Serializable
data class CreateGrantResponse(
    val grantId: String,
    val matchingCode: String,
    val password: String,
    val expiresAtEpochMillis: Long? = null,
)

@Serializable
data class ConnectRequest(
    val controllerDeviceId: String,
    val matchingCode: String,
    val password: String,
    val controllerPublicKey: String,
)

@Serializable
data class ConnectResponse(
    val sessionId: String,
    val controlledDeviceId: String,
    val requiresConfirmation: Boolean,
    val expiresAtEpochMillis: Long,
)

@Serializable
data class ConfirmSessionRequest(val approved: Boolean, val trustController: Boolean = false)

@Serializable
data class SessionStatusResponse(
    val sessionId: String,
    val status: SessionStatus,
    val peerDeviceId: String,
)

@Serializable
data class IncomingSessionResponse(
    val sessionId: String,
    val controllerDeviceId: String,
    val controllerDisplayName: String,
    val createdAtEpochMillis: Long,
)

@Serializable
enum class SessionStatus { PENDING, APPROVED, REJECTED, CLOSED }

@Serializable
data class ManagedDevice(
    val deviceId: String,
    val displayName: String,
    val online: Boolean,
    val trusted: Boolean,
    val lastSeenEpochMillis: Long,
)

@Serializable
data class ManagementResponse(
    val onlineDevices: List<ManagedDevice>,
    val authorizedDevices: List<ManagedDevice>,
)

@Serializable
data class RevokeGrantRequest(val peerDeviceId: String)

@Serializable
enum class SignalType {
    @SerialName("session_request") SESSION_REQUEST,
    @SerialName("session_status") SESSION_STATUS,
    @SerialName("offer") OFFER,
    @SerialName("answer") ANSWER,
    @SerialName("ice") ICE,
    @SerialName("control") CONTROL,
    @SerialName("hangup") HANGUP,
}

@Serializable
data class SignalEnvelope(
    val sessionId: String,
    val fromDeviceId: String,
    val toDeviceId: String,
    val type: SignalType,
    val payload: JsonElement,
)
