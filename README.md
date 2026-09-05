# build-redis-from-scratch

A Redis server written in Java from nothing — no Spring, no Netty, no Redis library. Plain
sockets, a hand-written RESP parser, and `java.util.concurrent`.

It speaks the real protocol, so `redis-cli` talks to it without knowing the difference.

```bash
mvn clean package
java -cp target/classes io.github.mesubash.Main --port 6379

# or as a replica of another instance
java -cp target/classes io.github.mesubash.Main --port 6380 --replicaof "localhost 6379"
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

**Server** `CONFIG GET` · `INFO` · `DBSIZE` · `FLUSHALL` · `SAVE` · `BGSAVE` · `PING` · `ECHO` · `COMMAND`

**Replication** `REPLCONF` · `PSYNC` · `WAIT`, and `--replicaof "host port"`

Keys expire lazily, values are binary-safe, and every command runs concurrently across
thread-per-connection workers.

String keys persist to an RDB snapshot on `SAVE`, and load on startup. The format is real
enough that `redis-server` reads our files and we read its.

A second instance started with `--replicaof "host port"` handshakes with the master, receives
a snapshot, then follows every write on the same connection.

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
| `RdbReader` / `RdbWriter` | the RDB snapshot format, strings only |
| `Replication` / `ReplicaClient` | master-side replica registry, replica-side follower |

Two boundaries carry the design: nothing outside `RespParser`/`RespWriter` touches `\r\n`, and
nothing outside `RedisStore` decides whether a key has expired.

## Running the tests

```bash
mvn test
```

295 tests. The ones worth reading are the concurrency cases in `RedisStoreTest` — concurrent
`INCR` and `RPUSH` that fail against a read-then-write implementation and pass against
`compute()` — and `WatchTest.everyMutatingCommandInvalidatesAWatch`, which catches a new write
command forgetting to bump the version counter.

## Not implemented

Consumer groups, `CONFIG SET`, set algebra (`SINTER`/`SUNION`/`SDIFF`), and AOF.

Replication does a full resync every time — there is no partial resync backlog, no chained
replicas, and `WAIT` returns the connected replica count rather than blocking for
acknowledgements.

RDB handles strings only — lists, hashes, sets, sorted sets and streams are skipped when
saving, because writing them needs redis's listpack and quicklist encodings. Snapshots also
carry a zero checksum, which redis accepts and treats as "verification disabled".

`SCAN` uses a sorted index as its cursor rather than redis's reverse-binary bucket walk — the
interface is right, the algorithm is a simplification, and it is marked as one in the code.

## Requirements

JDK 21+ (built against 26), Maven. No dependencies outside JUnit for tests.
