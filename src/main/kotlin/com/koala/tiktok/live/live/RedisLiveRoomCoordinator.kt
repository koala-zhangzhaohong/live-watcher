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
    private val activitiesKey = "$prefix:room-activity"
    private val settingsKey = "$prefix:settings"
    private val failuresKey = "$prefix:room-failures"
    private val failureReasonsKey = "$prefix:room-failure-reasons"
    private val recordExpirationsKey = "$prefix:room-record-expirations"

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
        redis.opsForZSet().addIfAbsent(
            recordExpirationsKey,
            liveId,
            (System.currentTimeMillis() + properties.roomRetentionSeconds * 1000).toDouble(),
        )
        if (state == DesiredRoomState.RUNNING) {
            redis.opsForZSet().add(activitiesKey, liveId, System.currentTimeMillis().toDouble())
        } else {
            redis.opsForZSet().remove(activitiesKey, liveId)
        }
    }

    override fun remove(liveId: String): Boolean {
        val removed = redis.opsForHash<String, String>().delete(roomsKey, liveId) > 0
        redis.opsForZSet().remove(activitiesKey, liveId)
        redis.opsForHash<String, String>().delete(failuresKey, liveId)
        redis.opsForHash<String, String>().delete(failureReasonsKey, liveId)
        redis.opsForZSet().remove(recordExpirationsKey, liveId)
        release(liveId)
        return removed
    }

    override fun rooms(): List<CoordinatedRoom> {
        val states = readStates()
        val activeInstances = refreshInstances()
        val failures = redis.opsForHash<String, String>().entries(failuresKey)
        val failureReasons = redis.opsForHash<String, String>().entries(failureReasonsKey)
        return states.entries.sortedBy { it.key }.map { (liveId, state) ->
            val leaseOwner = redis.opsForValue().get(leaseKey(liveId))
            val assigned =
                if (state == DesiredRoomState.RUNNING) {
                    leaseOwner ?: selectOwner(liveId, activeInstances)
                } else {
                    null
                }
            CoordinatedRoom(
                liveId, state, assigned, leaseOwner,
                redis.opsForZSet().score(activitiesKey, liveId)?.toLong(),
                failures[liveId]?.toLongOrNull() ?: 0,
                failureReasons[liveId],
                redis.opsForZSet().score(recordExpirationsKey, liveId)?.toLong()
                    ?: (System.currentTimeMillis() + properties.roomRetentionSeconds * 1000),
            )
        }
    }

    override fun inactivityTimeoutSeconds(): Long {
        val stored = redis.opsForHash<String, String>().get(settingsKey, INACTIVITY_TIMEOUT_FIELD)?.toLongOrNull()
        if (stored != null && stored > 0) return stored
        redis.opsForHash<String, String>().putIfAbsent(settingsKey, INACTIVITY_TIMEOUT_FIELD, properties.inactivityTimeoutSeconds.toString())
        return redis.opsForHash<String, String>().get(settingsKey, INACTIVITY_TIMEOUT_FIELD)?.toLongOrNull()
            ?: properties.inactivityTimeoutSeconds
    }

    override fun updateInactivityTimeoutSeconds(seconds: Long): Set<String> {
        require(seconds > 0) { "inactivityTimeoutSeconds must be greater than 0" }
        redis.opsForHash<String, String>().put(settingsKey, INACTIVITY_TIMEOUT_FIELD, seconds.toString())
        return expireInactiveRooms()
    }

    override fun touch(liveId: String): Boolean =
        redis.execute(TOUCH_SCRIPT, listOf(roomsKey, activitiesKey), liveId, System.currentTimeMillis().toString()) == 1L

    override fun expireInactiveRooms(): Set<String> =
        redis.execute(
            EXPIRE_SCRIPT,
            listOf(roomsKey, activitiesKey),
            (System.currentTimeMillis() - inactivityTimeoutSeconds() * 1000).toString(),
            "$prefix:lease:",
        ).orEmpty().toSet()

    override fun recordStartSucceeded(liveId: String) {
        redis.opsForHash<String, String>().delete(failuresKey, liveId)
        redis.opsForHash<String, String>().delete(failureReasonsKey, liveId)
    }

    override fun recordStartFailure(liveId: String, reason: String): Boolean =
        redis.execute(
            RECORD_FAILURE_SCRIPT,
            listOf(roomsKey, failuresKey, failureReasonsKey, activitiesKey, leaseKey(liveId)),
            liveId,
            instanceId,
            reason.take(MAX_FAILURE_REASON_LENGTH),
            properties.maxConsecutiveFailures.toString(),
        ) == 1L

    override fun purgeExpiredRooms(): Set<String> =
        redis.execute(
            PURGE_RECORDS_SCRIPT,
            listOf(roomsKey, activitiesKey, failuresKey, failureReasonsKey, recordExpirationsKey),
            System.currentTimeMillis().toString(),
            "$prefix:lease:",
        ).orEmpty().toSet()

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
                // A crashed/restarted instance may leave its lease behind until the
                // normal lease TTL expires. Reclaim it immediately when the owner is
                // no longer present in the heartbeat set. This only changes ownership;
                // the activity score is intentionally preserved so inactivity timing
                // continues from the original last query/start timestamp.
                val lease = leaseKey(liveId)
                val leaseOwner = redis.opsForValue().get(lease)
                if (leaseOwner != null && leaseOwner !in activeInstances) {
                    redis.delete(lease)
                }
                redis.opsForZSet().addIfAbsent(activitiesKey, liveId, System.currentTimeMillis().toDouble())
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
        private const val INACTIVITY_TIMEOUT_FIELD = "inactivityTimeoutSeconds"
        private const val MAX_FAILURE_REASON_LENGTH = 2000
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
        private val TOUCH_SCRIPT =
            DefaultRedisScript(
                """
                if redis.call('hget', KEYS[1], ARGV[1]) == 'RUNNING' then
                  redis.call('zadd', KEYS[2], ARGV[2], ARGV[1])
                  return 1
                end
                return 0
                """.trimIndent(),
                Long::class.java,
            )
        private val EXPIRE_SCRIPT =
            DefaultRedisScript(
                """
                local candidates = redis.call('zrangebyscore', KEYS[2], '-inf', ARGV[1])
                local expired = {}
                for _, liveId in ipairs(candidates) do
                  if redis.call('hget', KEYS[1], liveId) == 'RUNNING' then
                    redis.call('hset', KEYS[1], liveId, 'ENDED')
                    redis.call('del', ARGV[2] .. liveId)
                    table.insert(expired, liveId)
                  end
                  redis.call('zrem', KEYS[2], liveId)
                end
                return expired
                """.trimIndent(),
                List::class.java,
            ) as DefaultRedisScript<List<String>>
        private val RECORD_FAILURE_SCRIPT =
            DefaultRedisScript(
                """
                if redis.call('hget', KEYS[1], ARGV[1]) ~= 'RUNNING' or redis.call('get', KEYS[5]) ~= ARGV[2] then
                  return 0
                end
                local failures = redis.call('hincrby', KEYS[2], ARGV[1], 1)
                redis.call('hset', KEYS[3], ARGV[1], ARGV[3])
                if failures >= tonumber(ARGV[4]) then
                  redis.call('hset', KEYS[1], ARGV[1], 'FAILED')
                  redis.call('zrem', KEYS[4], ARGV[1])
                  redis.call('del', KEYS[5])
                  return 1
                end
                return 0
                """.trimIndent(),
                Long::class.java,
            )
        private val PURGE_RECORDS_SCRIPT =
            DefaultRedisScript(
                """
                local expired = redis.call('zrangebyscore', KEYS[5], '-inf', ARGV[1])
                for _, liveId in ipairs(expired) do
                  redis.call('hdel', KEYS[1], liveId)
                  redis.call('zrem', KEYS[2], liveId)
                  redis.call('hdel', KEYS[3], liveId)
                  redis.call('hdel', KEYS[4], liveId)
                  redis.call('zrem', KEYS[5], liveId)
                  redis.call('del', ARGV[2] .. liveId)
                end
                return expired
                """.trimIndent(),
                List::class.java,
            ) as DefaultRedisScript<List<String>>
    }
}
