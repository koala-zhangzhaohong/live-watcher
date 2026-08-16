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
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

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

    @Test
    fun `status query refreshes inactivity and shorter setting expires immediately`() {
        val fixture = fixture()
        fixture.service.start("95182733153")

        Thread.sleep(600)
        fixture.service.room("95182733153", refreshActivity = true)
        Thread.sleep(600)
        fixture.service.updateSettings(1)
        assertEquals(1, fixture.service.summary().total)

        Thread.sleep(500)
        fixture.service.reconcile()
        assertEquals(1, fixture.service.summary().total)
        assertEquals(1, fixture.service.summary().ended)
        assertTrue(fixture.factory.created.single().stopped)
    }

    @Test
    fun `stops retrying after three consecutive start failures and exposes reason`() {
        val fixture = fixture(failStarts = true)
        fixture.service.start("95182733153")
        fixture.service.reconcile()
        fixture.service.reconcile()

        val room = fixture.service.room("95182733153")!!
        assertEquals(DesiredRoomState.FAILED, room.desiredState)
        assertEquals(3L, room.consecutiveFailures)
        assertTrue(room.lastFailureReason!!.contains("test start failure"))
        assertEquals(0, fixture.service.summary().localListening)
    }

    @Test
    fun `live end callback marks room ended and stops listener`() {
        val fixture = fixture()
        fixture.service.start("95182733153")

        fixture.factory.created.single().endLive()

        assertEquals(DesiredRoomState.ENDED, fixture.service.room("95182733153")!!.desiredState)
        assertEquals(0, fixture.service.summary().localListening)
        assertTrue(fixture.factory.created.single().stopped)
    }

    private fun fixture(failStarts: Boolean = false): Fixture {
        val properties = DouyinLiveProperties(cookies = TEST_COOKIES, instanceId = "test-instance")
        val factory = RecordingLiveClientFactory(failStarts)
        val cookieService = mock(DouyinCookieService::class.java)
        `when`(cookieService.liveCookie()).thenReturn(TEST_COOKIES)
        return Fixture(
            service = DouyinLiveService(properties, factory, LocalLiveRoomCoordinator(properties), cookieService),
            factory = factory,
        )
    }

    private data class Fixture(
        val service: DouyinLiveService,
        val factory: RecordingLiveClientFactory,
    )

    private class RecordingLiveClientFactory(private val failStarts: Boolean) : LiveClientFactory {
        val created = CopyOnWriteArrayList<RecordingLiveClient>()

        override fun create(liveId: String, auth: DouyinAuth, onLiveEnded: () -> Unit): LiveClient =
            RecordingLiveClient(liveId, failStarts, onLiveEnded).also { created += it }
    }

    private class RecordingLiveClient(
        val liveId: String,
        private val failStart: Boolean,
        private val onLiveEnded: () -> Unit,
    ) : LiveClient {
        @Volatile var started = false
        @Volatile var stopped = false

        override fun start() {
            if (failStart) error("test start failure")
            started = true
        }

        override fun stop() {
            stopped = true
        }

        fun endLive() = onLiveEnded()
    }

    companion object {
        private const val TEST_COOKIES = "ttwid=test-ttwid; s_v_web_id=test-web-id; msToken=old-token"
    }
}
