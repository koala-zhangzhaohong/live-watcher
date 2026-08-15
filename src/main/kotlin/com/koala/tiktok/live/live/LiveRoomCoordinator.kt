package com.koala.tiktok.live.live

enum class DesiredRoomState {
    RUNNING,
    PAUSED,
    FAILED,
    ENDED,
}

data class CoordinatedRoom(
    val liveId: String,
    val state: DesiredRoomState,
    val assignedInstanceId: String?,
    val managingInstanceId: String?,
    val lastActivityEpochMs: Long?,
    val consecutiveFailures: Long,
    val lastFailureReason: String?,
    val recordExpiresAtEpochMs: Long,
)

data class CoordinatedInstance(
    val instanceId: String,
    val lastHeartbeatEpochMs: Long,
    val online: Boolean,
    val assignedRoomCount: Int,
    val assignedLiveIds: List<String>,
)

interface LiveRoomCoordinator {
    val instanceId: String
    val distributed: Boolean

    fun put(liveId: String, state: DesiredRoomState)

    fun remove(liveId: String): Boolean

    fun rooms(): List<CoordinatedRoom>

    fun inactivityTimeoutSeconds(): Long

    fun updateInactivityTimeoutSeconds(seconds: Long): Set<String>

    fun touch(liveId: String): Boolean

    fun expireInactiveRooms(): Set<String>

    fun recordStartSucceeded(liveId: String)

    fun recordStartFailure(liveId: String, reason: String): Boolean

    fun purgeExpiredRooms(): Set<String>

    fun instances(): List<CoordinatedInstance>

    fun reconcileAssignments(): Set<String>

    fun release(liveId: String)

    fun close()
}
