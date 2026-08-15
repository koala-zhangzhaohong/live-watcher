package com.koala.tiktok.live.live

import com.koala.tiktok.live.config.DouyinLiveProperties
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import java.lang.management.ManagementFactory
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap

@Component
@ConditionalOnProperty(prefix = "douyin.live", name = ["coordination-mode"], havingValue = "local", matchIfMissing = true)
class LocalLiveRoomCoordinator(
    properties: DouyinLiveProperties,
) : LiveRoomCoordinator {
    override val instanceId = properties.instanceId.ifBlank { defaultInstanceId() }
    override val distributed = false
    private val states = ConcurrentHashMap<String, DesiredRoomState>()

    override fun put(liveId: String, state: DesiredRoomState) {
        states[liveId] = state
    }

    override fun remove(liveId: String): Boolean = states.remove(liveId) != null

    override fun rooms(): List<CoordinatedRoom> =
        states.entries
            .sortedBy { it.key }
            .map { (liveId, state) ->
                val owner = instanceId.takeIf { state == DesiredRoomState.RUNNING }
                CoordinatedRoom(liveId, state, owner, owner)
            }

    override fun instances(): List<CoordinatedInstance> {
        val liveIds = states.filterValues { it == DesiredRoomState.RUNNING }.keys.sorted()
        return listOf(CoordinatedInstance(instanceId, System.currentTimeMillis(), true, liveIds.size, liveIds))
    }

    override fun reconcileAssignments(): Set<String> =
        states.filterValues { it == DesiredRoomState.RUNNING }.keys.toSet()

    override fun release(liveId: String) = Unit

    override fun close() = Unit
}

internal fun defaultInstanceId(): String =
    runCatching { InetAddress.getLocalHost().hostName }.getOrDefault("localhost") + "-" +
        ManagementFactory.getRuntimeMXBean().name.substringBefore('@')
