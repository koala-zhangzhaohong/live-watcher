package com.koala.tiktok.live.controller

import com.koala.tiktok.live.live.DouyinLiveService
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

data class StartLiveRequest(
    val liveId: String,
    val cookies: String? = null,
)

@RestController
@RequestMapping("/api/douyin/live")
class DouyinLiveController(
    private val liveService: DouyinLiveService,
) {
    @PostMapping("/start")
    fun start(@RequestBody request: StartLiveRequest): Map<String, Any> {
        val liveId = liveService.start(request.liveId, request.cookies ?: "")
        return mapOf("liveId" to liveId, "status" to "started")
    }

    @DeleteMapping("/{liveId}")
    fun stop(@PathVariable liveId: String): ResponseEntity<Map<String, Any>> {
        val stopped = liveService.stop(liveId)
        return if (stopped) {
            ResponseEntity.ok(mapOf("liveId" to liveId, "status" to "stopped"))
        } else {
            ResponseEntity.status(404).body(mapOf("liveId" to liveId, "status" to "not_found"))
        }
    }

    @DeleteMapping
    fun stopAll(): Map<String, Any> {
        liveService.stopAll()
        return mapOf("status" to "stopped")
    }

    @GetMapping
    fun active(): Map<String, Any> =
        mapOf("liveIds" to liveService.activeLiveIds())
}
