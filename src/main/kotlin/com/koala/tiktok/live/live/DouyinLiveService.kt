package com.koala.tiktok.live.live

import com.koala.tiktok.live.auth.DouyinAuth
import com.koala.tiktok.live.config.DouyinLiveProperties
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.util.concurrent.ConcurrentHashMap

data class LiveRoomView(
    val liveId: String,
    val desiredState: DesiredRoomState,
    val assignedInstanceId: String?,
    val managingInstanceId: String?,
    val managedByCurrentInstance: Boolean,
    val listeningOnThisInstance: Boolean,
)

data class LiveRoomSummary(
    val total: Int,
    val running: Int,
    val paused: Int,
    val localListening: Int,
    val instanceId: String,
    val distributed: Boolean,
    val rooms: List<LiveRoomView>,
    val instances: List<CoordinatedInstance>,
)

@Service
class DouyinLiveService(
    private val properties: DouyinLiveProperties,
    private val clientFactory: LiveClientFactory,
    private val coordinator: LiveRoomCoordinator,
) : ApplicationRunner {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val clients = ConcurrentHashMap<String, LiveClient>()
    private val cookieOverrides = ConcurrentHashMap<String, String>()
    private val lifecycleLock = Any()

    override fun run(args: ApplicationArguments) {
        if (properties.autoStart && properties.liveId.isNotBlank()) {
            start(properties.liveId, properties.cookies)
        } else {
            reconcile()
        }
    }

    fun start(
        liveId: String,
        cookies: String = properties.cookies,
    ): LiveRoomView {
        val normalizedId = liveId.trim()
        require(normalizedId.isNotBlank()) { "liveId must not be blank" }
        val effectiveCookies = cookies.ifBlank { properties.cookies }
        require(effectiveCookies.isNotBlank()) { "cookies must not be blank. Set DY_LIVE_COOKIES or pass cookies in request." }
        require(!coordinator.distributed || cookies.isBlank() || cookies == properties.cookies) {
            "Per-request cookies are not supported in redis coordination mode; configure DY_LIVE_COOKIES on every instance."
        }
        if (!coordinator.distributed && cookies.isNotBlank()) cookieOverrides[normalizedId] = effectiveCookies
        coordinator.put(normalizedId, DesiredRoomState.RUNNING)
        reconcile()
        return room(normalizedId)!!
    }

    fun pause(liveId: String): Boolean {
        if (coordinator.rooms().none { it.liveId == liveId }) return false
        coordinator.put(liveId, DesiredRoomState.PAUSED)
        coordinator.release(liveId)
        reconcile()
        return true
    }

    fun resume(liveId: String): Boolean {
        if (coordinator.rooms().none { it.liveId == liveId }) return false
        coordinator.put(liveId, DesiredRoomState.RUNNING)
        reconcile()
        return true
    }

    fun remove(liveId: String): Boolean {
        val removed = coordinator.remove(liveId)
        cookieOverrides.remove(liveId)
        reconcile()
        return removed
    }

    fun removeAll() {
        coordinator.rooms().forEach { coordinator.remove(it.liveId) }
        cookieOverrides.clear()
        reconcile()
    }

    fun summary(): LiveRoomSummary {
        val rooms = coordinator.rooms().map(::toView)
        return LiveRoomSummary(
            total = rooms.size,
            running = rooms.count { it.desiredState == DesiredRoomState.RUNNING },
            paused = rooms.count { it.desiredState == DesiredRoomState.PAUSED },
            localListening = clients.size,
            instanceId = coordinator.instanceId,
            distributed = coordinator.distributed,
            rooms = rooms,
            instances = coordinator.instances(),
        )
    }

    fun room(liveId: String): LiveRoomView? = coordinator.rooms().firstOrNull { it.liveId == liveId }?.let(::toView)

    fun activeLiveIds(): Set<String> = clients.keys.toSet()

    @Scheduled(fixedDelayString = "\${douyin.live.reconcile-seconds:5}", timeUnit = java.util.concurrent.TimeUnit.SECONDS)
    fun reconcile() {
        synchronized(lifecycleLock) {
            val assigned =
                runCatching { coordinator.reconcileAssignments() }.getOrElse { error ->
                    logger.error("Live room coordination failed on instance {}", coordinator.instanceId, error)
                    if (coordinator.distributed) {
                        clients.keys.toList().forEach(::stopLocal)
                    }
                    return
                }
            (clients.keys - assigned).forEach(::stopLocal)
            (assigned - clients.keys).forEach(::startLocal)
        }
    }

    private fun startLocal(liveId: String) {
        val cookies = cookieOverrides[liveId].orEmpty().ifBlank { properties.cookies }
        if (cookies.isBlank()) {
            logger.error("Cannot listen to liveId={}: DY_LIVE_COOKIES is not configured on instance {}", liveId, coordinator.instanceId)
            coordinator.release(liveId)
            return
        }
        runCatching {
            val client = clientFactory.create(liveId, DouyinAuth.prepare(cookies))
            client.start()
            clients[liveId] = client
            logger.info("Started Douyin live client: liveId={}, instanceId={}", liveId, coordinator.instanceId)
        }.onFailure {
            coordinator.release(liveId)
            logger.error("Failed to start Douyin live client: liveId={}, instanceId={}", liveId, coordinator.instanceId, it)
        }
    }

    private fun stopLocal(liveId: String) {
        clients.remove(liveId)?.let {
            runCatching { it.stop() }.onFailure { error -> logger.warn("Failed to stop liveId={}", liveId, error) }
            logger.info("Stopped Douyin live client: liveId={}, instanceId={}", liveId, coordinator.instanceId)
        }
        runCatching { coordinator.release(liveId) }
            .onFailure { logger.warn("Failed to release coordination lease: liveId={}", liveId, it) }
    }

    private fun toView(room: CoordinatedRoom) =
        LiveRoomView(
            liveId = room.liveId,
            desiredState = room.state,
            assignedInstanceId = room.assignedInstanceId,
            managingInstanceId = room.managingInstanceId,
            managedByCurrentInstance = room.managingInstanceId == coordinator.instanceId,
            listeningOnThisInstance = clients.containsKey(room.liveId),
        )

    @PreDestroy
    fun shutdown() {
        synchronized(lifecycleLock) {
            clients.keys.toList().forEach(::stopLocal)
            runCatching { coordinator.close() }
                .onFailure { logger.warn("Failed to unregister instance {}", coordinator.instanceId, it) }
        }
    }
}
