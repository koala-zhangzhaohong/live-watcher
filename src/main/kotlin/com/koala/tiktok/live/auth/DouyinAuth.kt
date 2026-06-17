package com.koala.tiktok.live.auth

import com.koala.tiktok.live.util.DouyinUtil

data class DouyinAuth private constructor(
    val cookie: MutableMap<String, String>,
    val cookieStr: String,
    val msToken: String,
) {
    companion object {
        fun prepare(cookieStr: String): DouyinAuth {
            val cookies = DouyinUtil.parseCookies(cookieStr)
            val msToken = cookies["msToken"] ?: DouyinUtil.generateMsToken()
            cookies["msToken"] = msToken
            val normalizedCookie = cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
            return DouyinAuth(cookies, normalizedCookie, msToken)
        }
    }
}
