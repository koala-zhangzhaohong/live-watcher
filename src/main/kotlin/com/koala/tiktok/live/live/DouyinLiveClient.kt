package com.koala.tiktok.live.live

import com.google.protobuf.ByteString
import com.koala.tiktok.live.api.DouyinApiClient
import com.koala.tiktok.live.auth.DouyinAuth
import com.koala.tiktok.live.config.DouyinLiveProperties
import com.koala.tiktok.live.proto.LiveProto
import com.koala.tiktok.live.util.Gzip
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString
import org.slf4j.LoggerFactory
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class DouyinLiveClient(
    private val liveId: String,
    private val auth: DouyinAuth,
    private val apiClient: DouyinApiClient,
    private val properties: DouyinLiveProperties,
    private val okHttpClient: okhttp3.OkHttpClient,
) : WebSocketListener() {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val scheduler = Executors.newSingleThreadScheduledExecutor()
    private val stopped = AtomicBoolean(false)
    private var webSocket: WebSocket? = null
    private var heartbeat: ScheduledFuture<*>? = null

    fun start() {
        stopped.set(false)
        startWebSocket()
    }

    fun stop() {
        stopped.set(true)
        heartbeat?.cancel(true)
        webSocket?.close(1000, "client stopped")
        scheduler.shutdownNow()
    }

    private fun startWebSocket() {
        val roomInfo = apiClient.getLiveInfo(auth, liveId)
        val detail = apiClient.getWebcastDetail(auth, roomInfo.userId, roomInfo.roomId, "https://live.douyin.com/$liveId")
        val initialResponse = LiveProto.LiveResponse.parseFrom(detail)
        val wsUrl = apiClient.buildWebSocketUrl(roomInfo, initialResponse.cursor, initialResponse.internalExt)
        val request = Request.Builder()
            .url(wsUrl)
            .headers(apiClient.webSocketHeaders(auth))
            .build()

        webSocket = okHttpClient.newWebSocket(request, this)
    }

    override fun onOpen(webSocket: WebSocket, response: Response) {
        logger.info("### opened ### liveId={}", liveId)
        heartbeat = scheduler.scheduleAtFixedRate(
            {
                try {
                    val frame = LiveProto.PushFrame.newBuilder()
                        .setPayloadType("hb")
                        .build()
                    webSocket.send(frame.toByteArray().toByteString())
                } catch (e: Exception) {
                    logger.warn("Heartbeat failed, closing websocket", e)
                    webSocket.close(1001, "heartbeat failed")
                }
            },
            0,
            properties.heartbeatSeconds,
            TimeUnit.SECONDS,
        )
    }

    override fun onMessage(webSocket: WebSocket, bytes: okio.ByteString) {
        try {
            val frame = LiveProto.PushFrame.parseFrom(bytes.toByteArray())
            val response = LiveProto.LiveResponse.parseFrom(Gzip.decompress(frame.payload.toByteArray()))
            if (response.needAck) {
                val ack = LiveProto.PushFrame.newBuilder()
                    .setPayloadType("ack")
                    .setPayload(ByteString.copyFromUtf8(response.internalExt))
                    .setLogId(frame.logId)
                    .build()
                webSocket.send(ack.toByteArray().toByteString())
            }
            response.messagesListList.forEach(::handleMessage)
        } catch (e: Exception) {
            logger.error("error", e)
        }
    }

    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
        logger.error("### error ###", t)
        reconnectIfNeeded()
    }

    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
        logger.warn("### closed ### status_code: {}, msg: {}", code, reason)
        reconnectIfNeeded()
    }

    private fun reconnectIfNeeded() {
        heartbeat?.cancel(true)
        if (!stopped.get() && properties.reconnectOnClose) {
            scheduler.schedule({ runCatching { startWebSocket() }.onFailure { logger.error("Reconnect failed", it) } }, 2, TimeUnit.SECONDS)
        }
    }

    private fun handleMessage(item: LiveProto.Message) {
        when (item.method) {
            "WebcastGiftMessage" -> {
                val message = LiveProto.GiftMessage.parseFrom(item.payload)
                logger.info(
                    "[礼物] SEC_UID = {} - {} 送给 {} - {} {} x {}",
                    message.user.secUid,
                    message.user.nickname,
                    message.toUser.secUid,
                    message.toUser.nickname,
                    message.gift.name,
                    message.comboCount,
                )
            }
            "WebcastChatMessage" -> {
                val message = LiveProto.ChatMessage.parseFrom(item.payload)
                logger.info("[消息] SEC_UID = {} - {} : {}", message.user.secUid, message.user.nickname, message.content)
            }
            "WebcastMemberMessage" -> {
                val message = LiveProto.MemberMessage.parseFrom(item.payload)
                logger.info("[进入] SEC_UID = {} - {} 进入直播间", message.user.secUid, message.user.nickname)
            }
            "WebcastLikeMessage" -> {
                val message = LiveProto.LikeMessage.parseFrom(item.payload)
                logger.info("[点赞] SEC_UID = {} - {} 点赞了 {} 次", message.user.secUid, message.user.nickname, message.count)
                logger.info("[点赞] 点赞总数 = {}", message.total)
            }
            "WebcastSocialMessage" -> {
                val message = LiveProto.SocialMessage.parseFrom(item.payload)
                if (message.action == 1L) {
                    logger.info("[关注] SEC_UID = {} - {} 关注主播", message.user.secUid, message.user.nickname)
                }
            }
            "WebcastRoomStatsMessage" -> {
                val message = LiveProto.RoomStatsMessage.parseFrom(item.payload)
                logger.info("[房间信息] {}", message.displayLong)
            }
            else -> logger.info("[未处理消息] {}", item.method)
        }
    }
}
