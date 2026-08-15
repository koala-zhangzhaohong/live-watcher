package com.koala.tiktok.live.live

import com.koala.tiktok.live.auth.DouyinAuth
import com.koala.tiktok.live.config.DouyinLiveProperties
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DouyinLiveServiceTest {
    @Test
    fun `starts multiple live rooms without replacing each other`() {
        val fixture = fixture()
        val executor = Executors.newFixedThreadPool(2)
        val startGate = CountDownLatch(1)

        listOf("95182733153", "95182744151").forEach { liveId ->
            executor.submit {
                startGate.await()
                fixture.service.start(liveId)
            }
        }

        startGate.countDown()
        executor.shutdown()
        assertTrue(executor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS))
        assertEquals(setOf("95182733153", "95182744151"), fixture.service.activeLiveIds())
        assertEquals(2, fixture.service.summary().total)
        assertEquals(2, fixture.service.summary().localListening)
        assertEquals(2, fixture.service.summary().instances.single().assignedRoomCount)
        assertEquals(listOf("95182733153", "95182744151"), fixture.service.summary().instances.single().assignedLiveIds)
        assertTrue(fixture.service.summary().rooms.all { it.managingInstanceId == "test-instance" })
        assertTrue(fixture.service.summary().rooms.all { it.managedByCurrentInstance })
    }

    @Test
    fun `starting an existing room is idempotent`() {
        val fixture = fixture()
        fixture.service.start("95182733153")
        fixture.service.start("95182733153")

        assertEquals(1, fixture.factory.created.size)
        assertTrue(fixture.factory.created.single().started)
        assertFalse(fixture.factory.created.single().stopped)
    }

    @Test
    fun `pauses resumes and removes a room`() {
        val fixture = fixture()
        fixture.service.start("95182733153")

        assertTrue(fixture.service.pause("95182733153"))
        assertEquals(0, fixture.service.summary().localListening)
        assertEquals(1, fixture.service.summary().paused)
        assertTrue(fixture.factory.created.single().stopped)

        assertTrue(fixture.service.resume("95182733153"))
        assertEquals(1, fixture.service.summary().localListening)
        assertEquals(2, fixture.factory.created.size)

        assertTrue(fixture.service.remove("95182733153"))
        assertEquals(0, fixture.service.summary().total)
        assertEquals(0, fixture.service.summary().localListening)
    }

    private fun fixture(): Fixture {
        val properties = DouyinLiveProperties(cookies = TEST_COOKIES, instanceId = "test-instance")
        val factory = RecordingLiveClientFactory()
        return Fixture(
            service = DouyinLiveService(properties, factory, LocalLiveRoomCoordinator(properties)),
            factory = factory,
        )
    }

    private data class Fixture(
        val service: DouyinLiveService,
        val factory: RecordingLiveClientFactory,
    )

    private class RecordingLiveClientFactory : LiveClientFactory {
        val created = CopyOnWriteArrayList<RecordingLiveClient>()

        override fun create(liveId: String, auth: DouyinAuth): LiveClient =
            RecordingLiveClient(liveId).also { created += it }
    }

    private class RecordingLiveClient(val liveId: String) : LiveClient {
        @Volatile var started = false
        @Volatile var stopped = false

        override fun start() {
            started = true
        }

        override fun stop() {
            stopped = true
        }
    }

    companion object {
        private const val TEST_COOKIES = "ttwid=test-ttwid; s_v_web_id=test-web-id; msToken=old-token"
    }
}
