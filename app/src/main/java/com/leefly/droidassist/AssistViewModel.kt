package com.leefly.droidassist

import android.app.Application
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.leefly.droidassist.data.AssistApi
import com.leefly.droidassist.data.DeviceIdentity
import com.leefly.droidassist.protocol.*
import com.leefly.droidassist.webrtc.WebRtcManager
import com.leefly.droidassist.webrtc.WebRtcConnectionStatus
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement

private val Application.dataStore by preferencesDataStore("droidassist")

data class AssistUiState(
    val serverUrl: String = "https://celebrities-aggregate-limiting-arrives.trycloudflare.com",
    val deviceId: String = "",
    val matchingCode: String = "",
    val recentMatchingCodes: List<String> = emptyList(),
    val grant: CreateGrantResponse? = null,
    val incomingSession: IncomingSessionResponse? = null,
    val remoteVideoReady: Boolean = false,
    val isScreenSharing: Boolean = false,
    val connectionStatus: WebRtcConnectionStatus = WebRtcConnectionStatus.IDLE,
    val management: ManagementResponse = ManagementResponse(emptyList(), emptyList()),
    val busy: Boolean = false,
    val message: String? = null,
)

class AssistViewModel(application: Application) : AndroidViewModel(application) {
    private val api = AssistApi()
    private val identity = DeviceIdentity(application)
    private val serverKey = stringPreferencesKey("server_url")
    private val deviceIdKey = stringPreferencesKey("device_id")
    private val tokenKey = stringPreferencesKey("device_token")
    private val matchingCodeKey = stringPreferencesKey("matching_code")
    private val recentMatchingCodesKey = stringPreferencesKey("recent_matching_codes")
    private val _state = MutableStateFlow(AssistUiState())
    val state: StateFlow<AssistUiState> = _state
    private val _screenCaptureRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val screenCaptureRequests: SharedFlow<Unit> = _screenCaptureRequests.asSharedFlow()
    private val json = Json { ignoreUnknownKeys = true }
    private var signalJob: Job? = null
    private val outgoingSignals = Channel<SignalEnvelope>(Channel.UNLIMITED)

    init {
        WebRtcManager.initialize(application) { outgoingSignals.trySend(it) }
        viewModelScope.launch {
            WebRtcManager.remoteVideoReady.collect { ready ->
                _state.update { it.copy(remoteVideoReady = ready) }
            }
        }
        viewModelScope.launch {
            WebRtcManager.connectionStatus.collect { status ->
                _state.update { it.copy(connectionStatus = status) }
            }
        }
        viewModelScope.launch {
            WebRtcManager.isScreenSharing.collect { sharing ->
                _state.update { it.copy(isScreenSharing = sharing) }
            }
        }
        viewModelScope.launch {
            val prefs = application.dataStore.data.first()
            _state.update {
                it.copy(
                    serverUrl = prefs[serverKey] ?: it.serverUrl,
                    deviceId = prefs[deviceIdKey].orEmpty(),
                    matchingCode = prefs[matchingCodeKey].orEmpty(),
                    recentMatchingCodes = prefs[recentMatchingCodesKey]
                        ?.let { value -> runCatching { json.decodeFromString<List<String>>(value) }.getOrNull() }
                        .orEmpty(),
                )
            }
            startSignalConnection()
        }
    }

    fun setServerUrl(value: String) = _state.update { it.copy(serverUrl = value) }
    fun clearMessage() = _state.update { it.copy(message = null) }
    fun clearRecentMatchingCodes() {
        _state.update { it.copy(recentMatchingCodes = emptyList()) }
        viewModelScope.launch {
            getApplication<Application>().dataStore.edit { it.remove(recentMatchingCodesKey) }
        }
    }

    fun register() = launchRequest {
        val current = _state.value
        val response = api.register(
            current.serverUrl,
            DeviceRegistrationRequest(identity.installationId, identity.displayName, identity.publicKey()),
        )
        getApplication<Application>().dataStore.edit {
            it[serverKey] = current.serverUrl.trim().trimEnd('/')
            it[deviceIdKey] = response.deviceId
            it[tokenKey] = response.deviceToken
            it[matchingCodeKey] = response.matchingCode
        }
        _state.update { it.copy(deviceId = response.deviceId, matchingCode = response.matchingCode, message = "设备已注册") }
        startSignalConnection()
    }

    fun createGrant(type: GrantType) = launchRequest {
        val token = token()
        val grant = api.createGrant(_state.value.serverUrl, token, type)
        _state.update { it.copy(grant = grant, message = if (type == GrantType.TEMPORARY) "临时授权已生成" else "永久授权已生成") }
    }

    fun connect(code: String, password: String) = launchRequest {
        val current = _state.value
        require(current.deviceId.isNotBlank()) { "请先注册本机" }
        val normalizedCode = code.filter(Char::isDigit)
        val response = api.connect(
            current.serverUrl,
            token(),
            ConnectRequest(current.deviceId, normalizedCode, password.trim().uppercase(), identity.publicKey()),
        )
        rememberMatchingCode(normalizedCode)
        WebRtcManager.configureController(response.sessionId, current.deviceId, response.controlledDeviceId)
        _state.update { it.copy(message = "连接请求已发送，会话 ${response.sessionId.take(8)} 等待对方确认") }
    }

    private suspend fun rememberMatchingCode(code: String) {
        val recent = (listOf(code) + _state.value.recentMatchingCodes.filterNot { it == code }).take(5)
        getApplication<Application>().dataStore.edit {
            it[recentMatchingCodesKey] = json.encodeToString(recent)
        }
        _state.update { it.copy(recentMatchingCodes = recent) }
    }

    fun respondToIncoming(approved: Boolean, trustController: Boolean) {
        val incoming = _state.value.incomingSession ?: return
        _state.update { it.copy(incomingSession = null, message = null) }
        viewModelScope.launch {
            runCatching {
                api.confirmSession(
                    _state.value.serverUrl,
                    token(),
                    incoming.sessionId,
                    approved,
                    trustController,
                )
            }.onSuccess { response ->
                if (response.status == SessionStatus.APPROVED) {
                    WebRtcManager.configureControlled(response.sessionId, _state.value.deviceId, response.peerDeviceId)
                    if (WebRtcManager.connectionStatus.value == WebRtcConnectionStatus.WAITING_FOR_CAPTURE) {
                        _screenCaptureRequests.tryEmit(Unit)
                    }
                }
                _state.update {
                    it.copy(message = if (response.status == SessionStatus.APPROVED) "已允许本次远程协助" else "已拒绝连接请求")
                }
            }.onFailure { error ->
                _state.update {
                    it.copy(
                        incomingSession = incoming,
                        message = error.message ?: "确认远程协助失败，请重试",
                    )
                }
            }
        }
    }

    fun refreshManagement() = launchRequest {
        val response = api.management(_state.value.serverUrl, token())
        _state.update { it.copy(management = response) }
    }

    fun revoke(peerId: String) = launchRequest {
        api.revoke(_state.value.serverUrl, token(), peerId)
        val response = api.management(_state.value.serverUrl, token())
        _state.update { it.copy(management = response, message = "授权已撤销") }
    }

    private suspend fun token(): String = getApplication<Application>().dataStore.data.first()[tokenKey]
        ?: error("请先注册本机")

    private fun startSignalConnection() {
        signalJob?.cancel()
        signalJob = viewModelScope.launch {
            while (isActive) {
                val preferences = getApplication<Application>().dataStore.data.first()
                val deviceId = preferences[deviceIdKey]
                val deviceToken = preferences[tokenKey]
                if (deviceId.isNullOrBlank() || deviceToken.isNullOrBlank()) return@launch
                runCatching {
                    api.listenSignals(_state.value.serverUrl, deviceId, deviceToken, outgoingSignals, ::handleSignal)
                }
                if (isActive) delay(2_000)
            }
        }
    }

    private suspend fun handleSignal(signal: SignalEnvelope) {
        when (signal.type) {
            SignalType.SESSION_REQUEST -> {
                val incoming = json.decodeFromJsonElement<IncomingSessionResponse>(signal.payload)
                _state.update { it.copy(incomingSession = incoming) }
            }
            SignalType.SESSION_STATUS -> {
                val status = json.decodeFromJsonElement<SessionStatusResponse>(signal.payload)
                val message = when (status.status) {
                    SessionStatus.APPROVED -> "对方已允许连接"
                    SessionStatus.REJECTED -> "对方已拒绝连接"
                    SessionStatus.CLOSED -> "会话已结束"
                    SessionStatus.PENDING -> "等待对方确认"
                }
                _state.update { it.copy(message = message) }
            }
            else -> WebRtcManager.handleSignal(signal)
        }
    }

    private fun launchRequest(block: suspend () -> Unit) {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, message = null) }
        viewModelScope.launch {
            runCatching { block() }.onFailure { error ->
                _state.update { it.copy(message = error.message ?: "请求失败") }
            }
            _state.update { it.copy(busy = false) }
        }
    }

    override fun onCleared() {
        signalJob?.cancel()
        outgoingSignals.close()
        WebRtcManager.stopSession()
        api.close()
    }
}
