package com.koala.tiktok.live

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

@SpringBootApplication
@EnableScheduling
class TiktokLiveApplication

fun main(args: Array<String>) {
    runApplication<TiktokLiveApplication>(*args)
}
