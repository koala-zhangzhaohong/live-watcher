package com.koala.tiktok.live.live

enum class DesiredRoomState {
    RUNNING,
    PAUSED,
}

data class CoordinatedRoom(
    val liveId: String,
    val state: DesiredRoomState,
    val assignedInstanceId: String?,
    val managingInstanceId: String?,
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

    fun instances(): List<CoordinatedInstance>

    fun reconcileAssignments(): Set<String>

    fun release(liveId: String)

    fun close()
}
