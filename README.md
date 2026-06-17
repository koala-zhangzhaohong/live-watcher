# Tiktok Live

Spring Boot 3 + Kotlin version of `dy_live/server.py` from `DouYin_Spider`.

## What Was Migrated

- Fetch live room metadata from `https://live.douyin.com/{liveId}`.
- Fetch initial protobuf cursor/internal extension from `/webcast/im/fetch/`.
- Generate live WebSocket signature using the original `dy_live_sign.js`.
- Generate `a_bogus` for the initial fetch using the original `dy_ab.js`.
- Connect to Douyin live WebSocket.
- Send heartbeat frames every 5 seconds.
- Parse `PushFrame` and gzip-compressed `LiveResponse` protobuf payloads.
- Send ACK frames when `needAck` is true.
- Log gift, chat, member-enter, like, follow, room-stats, and unhandled messages.
- Reconnect when the WebSocket closes, matching the Python behavior.

## Run

Install JDK 17+, then run:

```bash
export DY_LIVE_COOKIES='your douyin live cookies'
mvn spring-boot:run
```

Package a jar:

```bash
mvn clean package
java -jar target/tiktok-live-0.0.1-SNAPSHOT.jar
```

## API

Start a live room:

```bash
curl -X POST http://localhost:8080/api/douyin/live/start \
  -H 'Content-Type: application/json' \
  -d '{"liveId":"5200nono"}'
```

You can also pass cookies in the request body:

```bash
curl -X POST http://localhost:8080/api/douyin/live/start \
  -H 'Content-Type: application/json' \
  -d '{"liveId":"5200nono","cookies":"DY_LIVE_COOKIES value"}'
```

List active rooms:

```bash
curl http://localhost:8080/api/douyin/live
```

Stop one room:

```bash
curl -X DELETE http://localhost:8080/api/douyin/live/5200nono
```

Stop all rooms:

```bash
curl -X DELETE http://localhost:8080/api/douyin/live
```

## Auto Start

Set `douyin.live.auto-start=true`, `douyin.live.live-id`, and `DY_LIVE_COOKIES` in environment/config to connect automatically on application startup.
