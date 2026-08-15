package com.koala.tiktok.live.controller

import com.koala.tiktok.live.live.DouyinLiveService
import com.koala.tiktok.live.live.LiveRoomSummary
import com.koala.tiktok.live.live.LiveRoomView
import com.koala.tiktok.live.live.LiveSettings
import com.koala.tiktok.live.live.CoordinatedInstance
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

data class StartLiveRequest(
    val liveId: String,
    val cookies: String? = null,
)

data class UpdateLiveSettingsRequest(
    val inactivityTimeoutSeconds: Long,
)

@RestController
@RequestMapping("/api/douyin/live")
class DouyinLiveController(
    private val liveService: DouyinLiveService,
) {
    @PostMapping("/start")
    fun start(@RequestBody request: StartLiveRequest): LiveRoomView =
        liveService.start(request.liveId, request.cookies ?: "")

    @PostMapping("/{liveId}/pause")
    fun pause(@PathVariable liveId: String): ResponseEntity<LiveRoomView> =
        if (liveService.pause(liveId)) ResponseEntity.ok(liveService.room(liveId)!!) else ResponseEntity.notFound().build()

    @PostMapping("/{liveId}/resume")
    fun resume(@PathVariable liveId: String): ResponseEntity<LiveRoomView> =
        if (liveService.resume(liveId)) ResponseEntity.ok(liveService.room(liveId)!!) else ResponseEntity.notFound().build()

    @DeleteMapping("/{liveId}")
    fun remove(@PathVariable liveId: String): ResponseEntity<Void> =
        if (liveService.remove(liveId)) ResponseEntity.noContent().build() else ResponseEntity.notFound().build()

    @DeleteMapping
    fun removeAll(): ResponseEntity<Void> {
        liveService.removeAll()
        return ResponseEntity.noContent().build()
    }

    @GetMapping
    fun summary(): LiveRoomSummary = liveService.summary()

    @GetMapping("/settings")
    fun settings(): LiveSettings = liveService.settings()

    @PutMapping("/settings")
    fun updateSettings(@RequestBody request: UpdateLiveSettingsRequest): LiveSettings =
        liveService.updateSettings(request.inactivityTimeoutSeconds)

    @GetMapping("/{liveId}")
    fun room(@PathVariable liveId: String): ResponseEntity<LiveRoomView> =
        liveService.room(liveId, refreshActivity = true)?.let { ResponseEntity.ok(it) } ?: ResponseEntity.notFound().build()

    @GetMapping("/instances")
    fun instances(): List<CoordinatedInstance> = liveService.summary().instances
}
