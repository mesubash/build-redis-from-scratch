# build-redis-from-scratch

A Redis server written in Java from nothing — no Spring, no Netty, no Redis library. Plain
sockets, a hand-written RESP parser, and `java.util.concurrent`.

It speaks the real protocol, so `redis-cli` talks to it without knowing the difference.

```bash
mvn clean package
java -cp target/classes io.github.mesubash.Main --port 6379
```

```bash
$ redis-cli ping
PONG
$ redis-cli set name Subash ex 60
OK
$ redis-cli ttl name
(integer) 60
```

## What it does

**Strings** `SET` (with `EX`/`PX`) · `GET` · `MGET` · `MSET` · `SETNX` · `APPEND` · `STRLEN` ·
`GETDEL` · `INCR` · `DECR` · `INCRBY` · `DECRBY`

**Keys** `EXISTS` · `DEL` · `TYPE` · `KEYS` · `SCAN` · `TTL` · `PTTL` · `EXPIRE` · `PEXPIRE` · `PERSIST`

**Hashes** `HSET` · `HGET` · `HGETALL` · `HDEL` · `HEXISTS` · `HLEN` · `HKEYS` · `HVALS`

**Sets** `SADD` · `SREM` · `SMEMBERS` · `SISMEMBER` · `SCARD`

**Sorted sets** `ZADD` · `ZREM` · `ZSCORE` · `ZRANK` · `ZCARD` · `ZRANGE` (with `WITHSCORES`)

**Lists** `RPUSH` · `LPUSH` · `LRANGE` · `LLEN` · `LPOP` · `RPOP` · `LINDEX` · `BLPOP`

**Streams** `XADD` · `XRANGE` · `XLEN` · `XREAD` (with `BLOCK`)

**Transactions** `MULTI` · `EXEC` · `DISCARD` · `WATCH` · `UNWATCH`

**Pub/Sub** `SUBSCRIBE` · `UNSUBSCRIBE` · `PUBLISH`

**Server** `CONFIG GET` · `INFO` · `DBSIZE` · `FLUSHALL` · `PING` · `ECHO` · `COMMAND`

Keys expire lazily, values are binary-safe, and every command runs concurrently across
thread-per-connection workers.

## How it fits together

```text
socket bytes
     │
     ▼
RespParser ──────► String[]  ──────► CommandDispatcher ──────► RedisStore
                                            │                  ConcurrentHashMap
                                            ▼                  + expiry + types
                                       RespWriter
                                            │
                                            ▼
                                      socket bytes
```

| File | Job |
|---|---|
| `Main` | binds the port, accepts connections, one thread per client |
| `RespParser` | bytes to commands, handles partial and batched input |
| `RespWriter` | Java values to RESP bytes |
| `CommandDispatcher` | what commands mean; knows nothing about sockets |
| `RedisStore` | the data, expiry, and every atomic operation |
| `ClientSession` | per-connection state: transactions, subscriptions, the write lock |
| `PubSub` | channel registry |
| `RedisStream` / `StreamId` / `StreamEntry` | the stream type |
| `RedisSortedSet` | score index plus ordering, for sorted sets |
| `ServerConfig` | command line options, `CONFIG GET` |

Two boundaries carry the design: nothing outside `RespParser`/`RespWriter` touches `\r\n`, and
nothing outside `RedisStore` decides whether a key has expired.

## Running the tests

```bash
mvn test
```

264 tests. The ones worth reading are the concurrency cases in `RedisStoreTest` — concurrent
`INCR` and `RPUSH` that fail against a read-then-write implementation and pass against
`compute()` — and `WatchTest.everyMutatingCommandInvalidatesAWatch`, which catches a new write
command forgetting to bump the version counter.

## Not implemented

RDB persistence, replication, consumer groups, `CONFIG SET`, and set algebra
(`SINTER`/`SUNION`/`SDIFF`). Every one is a known gap rather than an oversight.

`SCAN` uses a sorted index as its cursor rather than redis's reverse-binary bucket walk — the
interface is right, the algorithm is a simplification, and it is marked as one in the code.

## Requirements

JDK 21+ (built against 26), Maven. No dependencies outside JUnit for tests.
