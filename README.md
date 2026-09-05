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

**Keys** `EXISTS` · `DEL` · `TYPE` · `KEYS` · `TTL` · `PTTL` · `EXPIRE` · `PEXPIRE` · `PERSIST`

**Lists** `RPUSH` · `LPUSH` · `LRANGE` · `LLEN` · `LPOP` · `RPOP` · `LINDEX` · `BLPOP`

**Streams** `XADD` · `XRANGE` · `XLEN` · `XREAD`

**Transactions** `MULTI` · `EXEC` · `DISCARD`

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

Two boundaries carry the design: nothing outside `RespParser`/`RespWriter` touches `\r\n`, and
nothing outside `RedisStore` decides whether a key has expired.

## Running the tests

```bash
mvn test
```

204 tests. The ones worth reading are the concurrency cases in `RedisStoreTest` — concurrent
`INCR` and `RPUSH` that fail against a read-then-write implementation and pass against
`compute()`.

## Not implemented

RDB persistence, replication, `WATCH`, consumer groups, `SCAN`, hashes, sets, sorted sets.
Every one of them is a known gap rather than an oversight.

## Requirements

JDK 21+ (built against 26), Maven. No dependencies outside JUnit for tests.
