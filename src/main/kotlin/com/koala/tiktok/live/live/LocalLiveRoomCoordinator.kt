package com.koala.tiktok.live.live

import com.koala.tiktok.live.config.DouyinLiveProperties
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import java.lang.management.ManagementFactory
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

@Component
@ConditionalOnProperty(prefix = "douyin.live", name = ["coordination-mode"], havingValue = "local", matchIfMissing = true)
class LocalLiveRoomCoordinator(
    properties: DouyinLiveProperties,
) : LiveRoomCoordinator {
    override val instanceId = properties.instanceId.ifBlank { defaultInstanceId() }
    override val distributed = false
    private val states = ConcurrentHashMap<String, DesiredRoomState>()
    private val activities = ConcurrentHashMap<String, Long>()
    private val inactivityTimeout = AtomicLong(properties.inactivityTimeoutSeconds)
    private val failures = ConcurrentHashMap<String, Long>()
    private val failureReasons = ConcurrentHashMap<String, String>()
    private val recordExpirations = ConcurrentHashMap<String, Long>()
    private val retentionMillis = properties.roomRetentionSeconds * 1000
    private val maxFailures = properties.maxConsecutiveFailures

    override fun put(liveId: String, state: DesiredRoomState) {
        states[liveId] = state
        recordExpirations.putIfAbsent(liveId, System.currentTimeMillis() + retentionMillis)
        if (state == DesiredRoomState.RUNNING) activities[liveId] = System.currentTimeMillis() else activities.remove(liveId)
    }

    override fun remove(liveId: String): Boolean {
        activities.remove(liveId)
        failures.remove(liveId)
        failureReasons.remove(liveId)
        recordExpirations.remove(liveId)
        return states.remove(liveId) != null
    }

    override fun rooms(): List<CoordinatedRoom> =
        states.entries
            .sortedBy { it.key }
            .map { (liveId, state) ->
                val owner = instanceId.takeIf { state == DesiredRoomState.RUNNING }
                CoordinatedRoom(
                    liveId, state, owner, owner, activities[liveId], failures[liveId] ?: 0,
                    failureReasons[liveId], recordExpirations[liveId] ?: (System.currentTimeMillis() + retentionMillis),
                )
            }

    override fun inactivityTimeoutSeconds(): Long = inactivityTimeout.get()

    override fun updateInactivityTimeoutSeconds(seconds: Long): Set<String> {
        require(seconds > 0) { "inactivityTimeoutSeconds must be greater than 0" }
        inactivityTimeout.set(seconds)
        return expireInactiveRooms()
    }

    override fun touch(liveId: String): Boolean {
        if (states[liveId] != DesiredRoomState.RUNNING) return false
        activities[liveId] = System.currentTimeMillis()
        return true
    }

    override fun expireInactiveRooms(): Set<String> {
        val cutoff = System.currentTimeMillis() - inactivityTimeout.get() * 1000
        val expired = activities.filter { (liveId, timestamp) -> timestamp <= cutoff && states[liveId] == DesiredRoomState.RUNNING }.keys
        expired.forEach { liveId ->
            states.replace(liveId, DesiredRoomState.RUNNING, DesiredRoomState.ENDED)
            activities.remove(liveId)
        }
        return expired
    }

    override fun recordStartSucceeded(liveId: String) {
        failures.remove(liveId)
        failureReasons.remove(liveId)
    }

    override fun recordStartFailure(liveId: String, reason: String): Boolean {
        if (states[liveId] != DesiredRoomState.RUNNING) return false
        val count = failures.merge(liveId, 1, Long::plus) ?: 1
        failureReasons[liveId] = reason
        if (count >= maxFailures) {
            states[liveId] = DesiredRoomState.FAILED
            activities.remove(liveId)
            return true
        }
        return false
    }

    override fun markEnded(liveId: String): Boolean {
        val ended = states.replace(liveId, DesiredRoomState.RUNNING, DesiredRoomState.ENDED)
        if (ended) activities.remove(liveId)
        return ended
    }

    override fun purgeExpiredRooms(): Set<String> {
        val now = System.currentTimeMillis()
        val expired = recordExpirations.filterValues { it <= now }.keys
        expired.forEach(::remove)
        return expired
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
