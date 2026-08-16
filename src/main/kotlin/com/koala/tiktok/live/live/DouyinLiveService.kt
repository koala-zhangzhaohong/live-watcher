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
    val lastActivityEpochMs: Long?,
    val expiresAtEpochMs: Long?,
    val consecutiveFailures: Long,
    val lastFailureReason: String?,
    val recordExpiresAtEpochMs: Long,
)

data class LiveSettings(
    val inactivityTimeoutSeconds: Long,
)

data class LiveRoomSummary(
    val total: Int,
    val running: Int,
    val paused: Int,
    val failed: Int,
    val ended: Int,
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
    private val cookieService: DouyinCookieService,
) : ApplicationRunner {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val clients = ConcurrentHashMap<String, LiveClient>()
    private val lifecycleLock = Any()

    override fun run(args: ApplicationArguments) {
        if (properties.autoStart && properties.liveId.isNotBlank()) {
            start(properties.liveId)
        } else {
            reconcile()
        }
    }

    fun start(liveId: String): LiveRoomView {
        val normalizedId = liveId.trim()
        require(normalizedId.isNotBlank()) { "liveId must not be blank" }
        require(cookieService.liveCookie().isNotBlank()) { "DY_LIVE_COOKIES is not configured in Redis or resources." }
        coordinator.recordStartSucceeded(normalizedId)
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
        coordinator.recordStartSucceeded(liveId)
        coordinator.put(liveId, DesiredRoomState.RUNNING)
        reconcile()
        return true
    }

    fun remove(liveId: String): Boolean {
        val removed = coordinator.remove(liveId)
        reconcile()
        return removed
    }

    fun removeAll() {
        coordinator.rooms().forEach { coordinator.remove(it.liveId) }
        reconcile()
    }

    fun summary(): LiveRoomSummary {
        val rooms = coordinator.rooms().map(::toView)
        return LiveRoomSummary(
            total = rooms.size,
            running = rooms.count { it.desiredState == DesiredRoomState.RUNNING },
            paused = rooms.count { it.desiredState == DesiredRoomState.PAUSED },
            failed = rooms.count { it.desiredState == DesiredRoomState.FAILED },
            ended = rooms.count { it.desiredState == DesiredRoomState.ENDED },
            localListening = clients.size,
            instanceId = coordinator.instanceId,
            distributed = coordinator.distributed,
            rooms = rooms,
            instances = coordinator.instances(),
        )
    }

    fun room(liveId: String, refreshActivity: Boolean = false): LiveRoomView? {
        if (refreshActivity) coordinator.touch(liveId)
        return coordinator.rooms().firstOrNull { it.liveId == liveId }?.let(::toView)
    }

    fun settings(): LiveSettings = LiveSettings(coordinator.inactivityTimeoutSeconds())

    fun updateSettings(inactivityTimeoutSeconds: Long): LiveSettings {
        coordinator.updateInactivityTimeoutSeconds(inactivityTimeoutSeconds)
        // Do not synchronously restart live clients here. A client connection can
        // block while negotiating the websocket, which would leave the settings
        // request (and the UI spinner) waiting indefinitely. The scheduled
        // reconciliation loop will apply the new timeout and assignments shortly.
        return settings()
    }

    fun updateCookies(
        dyCookie: String?,
        dyLiveCookie: String?,
    ): CookieUpdateResult = cookieService.update(dyCookie, dyLiveCookie)

    fun activeLiveIds(): Set<String> = clients.keys.toSet()

    @Scheduled(fixedDelayString = "\${douyin.live.reconcile-seconds:5}", timeUnit = java.util.concurrent.TimeUnit.SECONDS)
    fun reconcile() {
        synchronized(lifecycleLock) {
            val retentionExpired = runCatching { coordinator.purgeExpiredRooms() }.getOrElse { error ->
                logger.error("Failed to purge expired live room records on instance {}", coordinator.instanceId, error)
                emptySet()
            }
            if (retentionExpired.isNotEmpty()) logger.info("Purged expired live room records: liveIds={}", retentionExpired.sorted())
            val expired = runCatching { coordinator.expireInactiveRooms() }.getOrElse { error ->
                logger.error("Failed to expire inactive live rooms on instance {}", coordinator.instanceId, error)
                emptySet()
            }
            if (expired.isNotEmpty()) logger.info("Expired inactive live rooms: liveIds={}", expired.sorted())
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
        val cookies = cookieService.liveCookie()
        if (cookies.isBlank()) {
            logger.error("Cannot listen to liveId={}: DY_LIVE_COOKIES is not configured on instance {}", liveId, coordinator.instanceId)
            coordinator.release(liveId)
            return
        }
        runCatching {
            val client = clientFactory.create(liveId, DouyinAuth.prepare(cookies)) { liveEnded(liveId) }
            client.start()
            clients[liveId] = client
            coordinator.recordStartSucceeded(liveId)
            logger.info("Started Douyin live client: liveId={}, instanceId={}", liveId, coordinator.instanceId)
        }.onFailure {
            val reason = failureReason(it)
            val terminal = runCatching { coordinator.recordStartFailure(liveId, reason) }.getOrDefault(false)
            coordinator.release(liveId)
            logger.error("Failed to start Douyin live client: liveId={}, instanceId={}", liveId, coordinator.instanceId, it)
            if (terminal) logger.error("Stopped retrying liveId={} after {} consecutive failures", liveId, properties.maxConsecutiveFailures)
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

    private fun liveEnded(liveId: String) {
        synchronized(lifecycleLock) {
            if (coordinator.markEnded(liveId)) {
                logger.info("Ended listener because broadcast finished: liveId={}, instanceId={}", liveId, coordinator.instanceId)
            }
            stopLocal(liveId)
        }
    }

    private fun toView(room: CoordinatedRoom) =
        LiveRoomView(
            liveId = room.liveId,
            desiredState = room.state,
            assignedInstanceId = room.assignedInstanceId,
            managingInstanceId = room.managingInstanceId,
            managedByCurrentInstance = room.managingInstanceId == coordinator.instanceId,
            listeningOnThisInstance = clients.containsKey(room.liveId),
            lastActivityEpochMs = room.lastActivityEpochMs,
            expiresAtEpochMs = room.lastActivityEpochMs?.plus(coordinator.inactivityTimeoutSeconds() * 1000),
            consecutiveFailures = room.consecutiveFailures,
            lastFailureReason = room.lastFailureReason,
            recordExpiresAtEpochMs = room.recordExpiresAtEpochMs,
        )

    private fun failureReason(error: Throwable): String =
        generateSequence(error) { it.cause }
            .joinToString(" -> ") { cause ->
                cause::class.simpleName + (cause.message?.takeIf(String::isNotBlank)?.let { ": $it" } ?: "")
            }
            .take(2000)

    @PreDestroy
    fun shutdown() {
        synchronized(lifecycleLock) {
            clients.keys.toList().forEach(::stopLocal)
            runCatching { coordinator.close() }
                .onFailure { logger.warn("Failed to unregister instance {}", coordinator.instanceId, it) }
        }
    }
}
