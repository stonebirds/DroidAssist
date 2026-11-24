package com.leefly.droidassist.server

import com.leefly.droidassist.protocol.*
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.Types
import java.util.UUID

internal data class AuthenticatedDevice(val id: String, val matchingCode: String)

internal class Database(path: Path) : AutoCloseable {
    private val db: Connection

    init {
        path.parent?.let(Files::createDirectories)
        db = DriverManager.getConnection("jdbc:sqlite:$path")
        db.createStatement().use {
            it.execute("PRAGMA foreign_keys=ON")
            it.execute("PRAGMA journal_mode=WAL")
            it.executeUpdate("CREATE TABLE IF NOT EXISTS devices(id TEXT PRIMARY KEY,installation_id TEXT UNIQUE NOT NULL,display_name TEXT NOT NULL,public_key TEXT NOT NULL,matching_code TEXT UNIQUE NOT NULL,token_hash TEXT NOT NULL,online INTEGER NOT NULL DEFAULT 0,last_seen INTEGER NOT NULL)")
            it.executeUpdate("CREATE TABLE IF NOT EXISTS grants(id TEXT PRIMARY KEY,owner_id TEXT NOT NULL,peer_id TEXT,type TEXT NOT NULL,password_hash TEXT NOT NULL,expires_at INTEGER,consumed INTEGER NOT NULL DEFAULT 0,failed_attempts INTEGER NOT NULL DEFAULT 0,trusted INTEGER NOT NULL DEFAULT 0,created_at INTEGER NOT NULL,FOREIGN KEY(owner_id) REFERENCES devices(id) ON DELETE CASCADE)")
            it.executeUpdate("CREATE TABLE IF NOT EXISTS sessions(id TEXT PRIMARY KEY,controller_id TEXT NOT NULL,controlled_id TEXT NOT NULL,grant_id TEXT NOT NULL,status TEXT NOT NULL,expires_at INTEGER NOT NULL,created_at INTEGER NOT NULL)")
        }
    }

    @Synchronized
    fun register(request: DeviceRegistrationRequest): DeviceRegistrationResponse {
        val existing = db.prepareStatement("SELECT id,matching_code FROM devices WHERE installation_id=?").use { q ->
            q.setString(1, request.installationId)
            q.executeQuery().use { if (it.next()) it.getString(1) to it.getString(2) else null }
        }
        val token = Security.token()
        val now = System.currentTimeMillis()
        if (existing != null) {
            db.prepareStatement("UPDATE devices SET display_name=?,public_key=?,token_hash=?,last_seen=? WHERE id=?").use {
                it.setString(1, request.displayName); it.setString(2, request.publicKey); it.setString(3, Security.hash(token))
                it.setLong(4, now); it.setString(5, existing.first); it.executeUpdate()
            }
            return DeviceRegistrationResponse(existing.first, existing.second, token)
        }
        val id = UUID.randomUUID().toString()
        var code: String
        do code = Security.numericCode(9) while (codeExists(code))
        db.prepareStatement("INSERT INTO devices VALUES(?,?,?,?,?,?,0,?)").use {
            it.setString(1, id); it.setString(2, request.installationId); it.setString(3, request.displayName)
            it.setString(4, request.publicKey); it.setString(5, code); it.setString(6, Security.hash(token)); it.setLong(7, now)
            it.executeUpdate()
        }
        return DeviceRegistrationResponse(id, code, token)
    }

    @Synchronized
    fun authenticate(token: String?): AuthenticatedDevice? {
        if (token.isNullOrBlank()) return null
        db.prepareStatement("SELECT id,matching_code,token_hash FROM devices").use { q ->
            q.executeQuery().use { rows ->
                while (rows.next()) if (Security.verify(token, rows.getString(3))) {
                    touch(rows.getString(1))
                    return AuthenticatedDevice(rows.getString(1), rows.getString(2))
                }
            }
        }
        return null
    }

    @Synchronized
    fun createGrant(owner: AuthenticatedDevice, request: CreateGrantRequest): CreateGrantResponse {
        val id = UUID.randomUUID().toString()
        val password = Security.password()
        val now = System.currentTimeMillis()
        val expiresAt = if (request.type == GrantType.TEMPORARY) now + request.ttlSeconds.coerceIn(60, 3600) * 1000 else null
        db.prepareStatement("INSERT INTO grants(id,owner_id,type,password_hash,expires_at,created_at) VALUES(?,?,?,?,?,?)").use {
            it.setString(1, id); it.setString(2, owner.id); it.setString(3, request.type.name); it.setString(4, Security.hash(password))
            if (expiresAt == null) it.setNull(5, Types.BIGINT) else it.setLong(5, expiresAt)
            it.setLong(6, now); it.executeUpdate()
        }
        return CreateGrantResponse(id, owner.matchingCode, password, expiresAt)
    }

    @Synchronized
    fun connect(request: ConnectRequest): ConnectResponse? {
        val now = System.currentTimeMillis()
        val sql = "SELECT g.id,g.owner_id,g.type,g.password_hash,g.expires_at,g.failed_attempts,g.consumed,g.trusted FROM grants g JOIN devices d ON d.id=g.owner_id WHERE d.matching_code=? ORDER BY g.created_at DESC"
        db.prepareStatement(sql).use { q ->
            q.setString(1, request.matchingCode)
            q.executeQuery().use { rows ->
                while (rows.next()) {
                    val grantId = rows.getString(1)
                    val expiresAt = rows.getLong(5).let { if (rows.wasNull()) null else it }
                    if (rows.getInt(6) >= 5 || rows.getInt(7) != 0 || (expiresAt != null && expiresAt <= now)) continue
                    if (!Security.verify(request.password, rows.getString(4))) {
                        db.prepareStatement("UPDATE grants SET failed_attempts=failed_attempts+1 WHERE id=?").use {
                            it.setString(1, grantId); it.executeUpdate()
                        }
                        continue
                    }
                    if (rows.getString(3) == GrantType.TEMPORARY.name) db.prepareStatement("UPDATE grants SET consumed=1 WHERE id=?").use {
                        it.setString(1, grantId); it.executeUpdate()
                    }
                    val sessionId = UUID.randomUUID().toString()
                    val sessionExpiry = now + 30 * 60 * 1000
                    db.prepareStatement("INSERT INTO sessions VALUES(?,?,?,?,?,?,?)").use {
                        it.setString(1, sessionId); it.setString(2, request.controllerDeviceId); it.setString(3, rows.getString(2))
                        it.setString(4, grantId); it.setString(5, SessionStatus.PENDING.name); it.setLong(6, sessionExpiry); it.setLong(7, now); it.executeUpdate()
                    }
                    return ConnectResponse(sessionId, rows.getString(2), rows.getInt(8) == 0, sessionExpiry)
                }
            }
        }
        return null
    }

    @Synchronized
    fun confirm(deviceId: String, sessionId: String, request: ConfirmSessionRequest): SessionStatusResponse? {
        val row = session(sessionId) ?: return null
        if (row.controlledId != deviceId || row.status != SessionStatus.PENDING) return null
        val status = if (request.approved) SessionStatus.APPROVED else SessionStatus.REJECTED
        db.prepareStatement("UPDATE sessions SET status=? WHERE id=?").use {
            it.setString(1, status.name); it.setString(2, sessionId); it.executeUpdate()
        }
        if (request.approved && request.trustController) db.prepareStatement("UPDATE grants SET peer_id=?,trusted=1 WHERE id=?").use {
            it.setString(1, row.controllerId); it.setString(2, row.grantId); it.executeUpdate()
        }
        return SessionStatusResponse(sessionId, status, row.controllerId)
    }

    @Synchronized
    fun status(deviceId: String, sessionId: String): SessionStatusResponse? {
        val row = session(sessionId) ?: return null
        val peer = when (deviceId) { row.controllerId -> row.controlledId; row.controlledId -> row.controllerId; else -> return null }
        return SessionStatusResponse(sessionId, row.status, peer)
    }

    @Synchronized
    fun pendingSession(deviceId: String): IncomingSessionResponse? = db.prepareStatement(
        "SELECT s.id,s.controller_id,d.display_name,s.created_at FROM sessions s JOIN devices d ON d.id=s.controller_id WHERE s.controlled_id=? AND s.status=? AND s.expires_at>? ORDER BY s.created_at LIMIT 1"
    ).use { q ->
        q.setString(1, deviceId)
        q.setString(2, SessionStatus.PENDING.name)
        q.setLong(3, System.currentTimeMillis())
        q.executeQuery().use {
            if (it.next()) IncomingSessionResponse(it.getString(1), it.getString(2), it.getString(3), it.getLong(4)) else null
        }
    }

    @Synchronized
    fun setOnline(deviceId: String, online: Boolean) {
        db.prepareStatement("UPDATE devices SET online=?,last_seen=? WHERE id=?").use {
            it.setInt(1, if (online) 1 else 0); it.setLong(2, System.currentTimeMillis()); it.setString(3, deviceId); it.executeUpdate()
        }
    }

    @Synchronized
    fun management(deviceId: String): ManagementResponse {
        val online = mutableListOf<ManagedDevice>()
        db.prepareStatement("SELECT id,display_name,last_seen FROM devices WHERE id<>? AND online=1").use { q ->
            q.setString(1, deviceId); q.executeQuery().use { rows ->
                while (rows.next()) online += ManagedDevice(rows.getString(1), rows.getString(2), true, false, rows.getLong(3))
            }
        }
        val authorized = mutableListOf<ManagedDevice>()
        db.prepareStatement("SELECT d.id,d.display_name,d.online,d.last_seen,g.trusted FROM grants g JOIN devices d ON d.id=g.peer_id WHERE g.owner_id=? AND g.peer_id IS NOT NULL").use { q ->
            q.setString(1, deviceId); q.executeQuery().use { rows ->
                while (rows.next()) authorized += ManagedDevice(rows.getString(1), rows.getString(2), rows.getInt(3) == 1, rows.getInt(5) == 1, rows.getLong(4))
            }
        }
        return ManagementResponse(online, authorized)
    }

    @Synchronized
    fun revoke(deviceId: String, peerId: String): Boolean = db.prepareStatement("DELETE FROM grants WHERE owner_id=? AND peer_id=?").use {
        it.setString(1, deviceId); it.setString(2, peerId); it.executeUpdate() > 0
    }

    private data class SessionRow(val controllerId: String, val controlledId: String, val grantId: String, val status: SessionStatus)
    private fun session(id: String): SessionRow? = db.prepareStatement("SELECT controller_id,controlled_id,grant_id,status FROM sessions WHERE id=?").use { q ->
        q.setString(1, id); q.executeQuery().use { if (it.next()) SessionRow(it.getString(1), it.getString(2), it.getString(3), SessionStatus.valueOf(it.getString(4))) else null }
    }
    private fun codeExists(code: String): Boolean = db.prepareStatement("SELECT 1 FROM devices WHERE matching_code=?").use {
        it.setString(1, code); it.executeQuery().use { rows -> rows.next() }
    }
    private fun touch(id: String) = db.prepareStatement("UPDATE devices SET last_seen=? WHERE id=?").use {
        it.setLong(1, System.currentTimeMillis()); it.setString(2, id); it.executeUpdate()
    }
    override fun close() = db.close()
}
