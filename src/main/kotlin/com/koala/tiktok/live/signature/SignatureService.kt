package com.koala.tiktok.live.signature

import com.koala.tiktok.live.util.DouyinUtil
import org.graalvm.polyglot.Context
import org.graalvm.polyglot.HostAccess
import org.slf4j.LoggerFactory
import org.springframework.core.io.ClassPathResource
import org.springframework.stereotype.Service
import java.nio.charset.StandardCharsets

@Service
class SignatureService {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val lock = Any()
    private val liveSignContext: Context by lazy { createContext("static/dy_live_sign.js") }
    private val aBogusContext: Context by lazy { createContext("static/dy_ab.js") }

    fun generateSignature(roomId: String, userUniqueId: String): String {
        val raw = "live_id=1,aid=6383,version_code=180800,webcast_sdk_version=1.0.15," +
            "room_id=$roomId,sub_room_id=,sub_channel_id=,did_rule=3,user_unique_id=$userUniqueId," +
            "device_platform=web,device_type=,ac=,identity=audience"
        val xMsStub = DouyinUtil.md5(raw)
        return synchronized(lock) {
            val result = liveSignContext.getBindings("js").getMember("get_signature").execute(xMsStub)
            val xBogus = result.getMember("X-Bogus")?.asString()
            require(!xBogus.isNullOrBlank()) { "dy_live_sign.js did not return X-Bogus" }
            xBogus
        }
    }

    fun generateABogus(params: Map<String, String>, data: Map<String, String> = emptyMap()): String {
        val query = DouyinUtil.spliceUrl(params)
        val body = if (data.isEmpty()) "" else DouyinUtil.spliceUrl(data)
        return synchronized(lock) {
            aBogusContext.getBindings("js").getMember("get_ab").execute(query, body).asString()
        }
    }

    private fun createContext(resourcePath: String): Context {
        val resource = ClassPathResource(resourcePath)
        val script = resource.inputStream.use { it.readBytes().toString(StandardCharsets.UTF_8) }
        val context = Context.newBuilder("js")
            .allowHostAccess(HostAccess.NONE)
            .allowIO(false)
            .build()
        synchronized(lock) {
            context.eval(
                "js",
                """
                var global = globalThis;
                var self = globalThis;
                var console = {log:function(){},warn:function(){},error:function(){}};
                var require = function(){ return {}; };
                var performance = {
                    timeOrigin: Date.now(),
                    now: function(){ return Date.now() - this.timeOrigin; },
                    timing: { navigationStart: Date.now() }
                };
                var __storage = function(){
                    var data = {};
                    return {
                        getItem: function(key){ return Object.prototype.hasOwnProperty.call(data, key) ? data[key] : null; },
                        setItem: function(key, value){ data[key] = String(value); },
                        removeItem: function(key){ delete data[key]; },
                        clear: function(){ data = {}; }
                    };
                };
                var localStorage = __storage();
                var sessionStorage = __storage();
                var setTimeout = function(callback){
                    if (typeof callback === 'function') {
                        callback();
                    }
                    return 0;
                };
                var clearTimeout = function(){};
                var crypto = {
                    getRandomValues: function(array) {
                        for (var i = 0; i < array.length; i++) {
                            array[i] = Math.floor(Math.random() * 256);
                        }
                        return array;
                    }
                };
                globalThis.performance = performance;
                globalThis.localStorage = localStorage;
                globalThis.sessionStorage = sessionStorage;
                globalThis.setTimeout = setTimeout;
                globalThis.clearTimeout = clearTimeout;
                globalThis.crypto = crypto;
                """.trimIndent(),
            )
            context.eval("js", script)
        }
        logger.info("Loaded Douyin script: {}", resourcePath)
        return context
    }
}
