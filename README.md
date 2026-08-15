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

List and count all managed rooms:

```bash
curl http://localhost:8080/api/douyin/live
```

The response includes global `total`, `running`, and `paused` counts, this instance's
`localListening` count, each room's target `assignedInstanceId`, and the actual Redis
lease owner in `managingInstanceId`.

Pause and resume a room without deleting its configuration:

```bash
curl -X POST http://localhost:8080/api/douyin/live/5200nono/pause
curl -X POST http://localhost:8080/api/douyin/live/5200nono/resume
```

Inspect one room:

```bash
curl http://localhost:8080/api/douyin/live/5200nono
```

Inspect active instances, heartbeat timestamps, and assigned room load:

```bash
curl http://localhost:8080/api/douyin/live/instances
```

Delete one room:

```bash
curl -X DELETE http://localhost:8080/api/douyin/live/5200nono
```

Delete all rooms:

```bash
curl -X DELETE http://localhost:8080/api/douyin/live
```

## Auto Start

Set `douyin.live.auto-start=true`, `douyin.live.live-id`, and `DY_LIVE_COOKIES` in environment/config to connect automatically on application startup.

## Multiple Instances

Use Redis coordination when running more than one application instance. All instances
must point to the same Redis database and configure the same `DY_LIVE_COOKIES`; cookies
are deliberately not stored in Redis.

The application uses the `test` profile by default. Set `SPRING_PROFILES_ACTIVE=prod`
for production. Both profiles connect to YM System's online Redis at
`116.255.208.81:55000`. Following YM System's convention, test uses Redis database
`1` and production uses database `0`; the key prefix also contains `test` or `prod`,
so a database mistake cannot mix room state. Set `REDIS_PASSWORD` to the online Redis
password before starting either profile.

```bash
export DY_LIVE_COOKIES='your douyin live cookies'
export DY_LIVE_COORDINATION_MODE=redis
export DY_LIVE_INSTANCE_ID="$(hostname)-instance-1"
export SPRING_PROFILES_ACTIVE=prod
mvn spring-boot:run
```

Give every process a unique `DY_LIVE_INSTANCE_ID`. Each process publishes a heartbeat.
Running rooms are distributed using rendezvous hashing, while a per-room Redis lease
prevents duplicate listeners during rebalancing. When an instance misses its heartbeat,
its rooms are reassigned automatically after `instance-timeout-seconds`.

Relevant settings:

| Setting | Default | Purpose |
| --- | ---: | --- |
| `douyin.live.coordination-mode` | `local` | Use `redis` for multiple instances |
| `douyin.live.reconcile-seconds` | `5` | Assignment reconciliation interval |
| `douyin.live.instance-timeout-seconds` | `20` | Time before an instance is considered unavailable |
| `douyin.live.lease-seconds` | `15` | Per-room ownership lease; must exceed reconciliation interval |
| `douyin.live.redis-key-prefix` | `tiktok-live:<profile>` | Namespace for shared Redis keys |
