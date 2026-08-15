package com.koala.tiktok.live.live

import com.koala.tiktok.live.config.DouyinLiveProperties
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.DefaultRedisScript
import org.springframework.stereotype.Component
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.time.Duration

@Component
@ConditionalOnProperty(prefix = "douyin.live", name = ["coordination-mode"], havingValue = "redis")
class RedisLiveRoomCoordinator(
    private val redis: StringRedisTemplate,
    private val properties: DouyinLiveProperties,
) : LiveRoomCoordinator {
    private val logger = LoggerFactory.getLogger(javaClass)
    override val instanceId = properties.instanceId.ifBlank { defaultInstanceId() }
    override val distributed = true
    private val prefix = properties.redisKeyPrefix.trimEnd(':')
    private val roomsKey = "$prefix:rooms"
    private val instancesKey = "$prefix:instances"

    init {
        require(properties.leaseSeconds > properties.reconcileSeconds) {
            "douyin.live.lease-seconds must be greater than reconcile-seconds"
        }
        require(properties.instanceTimeoutSeconds > properties.reconcileSeconds) {
            "douyin.live.instance-timeout-seconds must be greater than reconcile-seconds"
        }
    }

    override fun put(liveId: String, state: DesiredRoomState) {
        redis.opsForHash<String, String>().put(roomsKey, liveId, state.name)
    }

    override fun remove(liveId: String): Boolean {
        val removed = redis.opsForHash<String, String>().delete(roomsKey, liveId) > 0
        release(liveId)
        return removed
    }

    override fun rooms(): List<CoordinatedRoom> {
        val states = readStates()
        val activeInstances = refreshInstances()
        return states.entries.sortedBy { it.key }.map { (liveId, state) ->
            val leaseOwner = redis.opsForValue().get(leaseKey(liveId))
            val assigned =
                if (state == DesiredRoomState.RUNNING) {
                    leaseOwner ?: selectOwner(liveId, activeInstances)
                } else {
                    null
                }
            CoordinatedRoom(liveId, state, assigned, leaseOwner)
        }
    }

    override fun instances(): List<CoordinatedInstance> {
        val now = System.currentTimeMillis()
        val activeInstances = refreshInstancesWithHeartbeat()
        val runningRooms = readStates().filterValues { it == DesiredRoomState.RUNNING }.keys
        val assignments = activeInstances.keys.associateWith { instance ->
            runningRooms.filter { selectOwner(it, activeInstances.keys) == instance }.sorted()
        }
        return activeInstances.entries
            .map { (instance, heartbeat) ->
                val assigned = assignments[instance].orEmpty()
                CoordinatedInstance(instance, heartbeat, now - heartbeat <= properties.instanceTimeoutSeconds * 1000, assigned.size, assigned)
            }
            .sortedBy { it.instanceId }
    }

    override fun reconcileAssignments(): Set<String> {
        val activeInstances = refreshInstances()
        val owned = mutableSetOf<String>()
        readStates().forEach { (liveId, state) ->
            if (state == DesiredRoomState.RUNNING && selectOwner(liveId, activeInstances) == instanceId) {
                if (acquireOrRenew(liveId)) owned += liveId
            } else {
                release(liveId)
            }
        }
        return owned
    }

    override fun release(liveId: String) {
        redis.execute(RELEASE_SCRIPT, listOf(leaseKey(liveId)), instanceId)
    }

    override fun close() {
        redis.opsForZSet().remove(instancesKey, instanceId)
    }

    private fun refreshInstances(): Set<String> {
        return refreshInstancesWithHeartbeat().keys
    }

    private fun refreshInstancesWithHeartbeat(): Map<String, Long> {
        val now = System.currentTimeMillis()
        redis.opsForZSet().add(instancesKey, instanceId, now.toDouble())
        redis.opsForZSet().removeRangeByScore(instancesKey, 0.0, (now - properties.instanceTimeoutSeconds * 1000).toDouble())
        return redis.opsForZSet().rangeWithScores(instancesKey, 0, -1).orEmpty()
            .mapNotNull { tuple -> tuple.value?.let { it to (tuple.score?.toLong() ?: 0L) } }
            .toMap()
            .plus(instanceId to now)
    }

    private fun readStates(): Map<String, DesiredRoomState> =
        redis.opsForHash<String, String>().entries(roomsKey).mapNotNull { (liveId, value) ->
            runCatching { liveId to DesiredRoomState.valueOf(value) }
                .onFailure { logger.warn("Ignoring invalid room state in Redis: liveId={}, state={}", liveId, value) }
                .getOrNull()
        }.toMap()

    private fun acquireOrRenew(liveId: String): Boolean =
        redis.execute(
            ACQUIRE_SCRIPT,
            listOf(leaseKey(liveId)),
            instanceId,
            Duration.ofSeconds(properties.leaseSeconds).toMillis().toString(),
        ) == 1L

    private fun leaseKey(liveId: String) = "$prefix:lease:$liveId"

    private fun selectOwner(liveId: String, instances: Set<String>): String? =
        instances.maxWithOrNull { left, right -> java.lang.Long.compareUnsigned(score(liveId, left), score(liveId, right)) }

    private fun score(liveId: String, candidate: String): Long {
        val digest = MessageDigest.getInstance("SHA-256").digest("$liveId\u0000$candidate".toByteArray())
        return ByteBuffer.wrap(digest).long
    }

    companion object {
        private val ACQUIRE_SCRIPT =
            DefaultRedisScript(
                """
                local owner = redis.call('get', KEYS[1])
                if not owner or owner == ARGV[1] then
                  redis.call('psetex', KEYS[1], ARGV[2], ARGV[1])
                  return 1
                end
                return 0
                """.trimIndent(),
                Long::class.java,
            )
        private val RELEASE_SCRIPT =
            DefaultRedisScript(
                """
                if redis.call('get', KEYS[1]) == ARGV[1] then
                  return redis.call('del', KEYS[1])
                end
                return 0
                """.trimIndent(),
                Long::class.java,
            )
    }
}
