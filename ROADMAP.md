# Roadmap and status

What this server does, what it deliberately does not, and how to add the next piece.

Updated as the codebase moves. Last checked against the working tree on 2026-09-06.

## Status at a glance

| | |
|---|---|
| Commands | 67 |
| Data types | strings, lists, hashes, sets, sorted sets, streams |
| Tests | 295, all passing |
| Source | roughly 2,500 lines, 18 classes |
| Dependencies | JUnit, test scope only |
| Interoperability | `redis-cli` works unmodified. `redis-server` reads and writes our RDB files |

Real Redis has roughly 240 commands. Counting commands, this is about a quarter of it. Counting the
mechanisms that make Redis work, it is most of them, because the remaining commands are mostly new
names over machinery that already exists here.

## Implemented

### Strings and numbers
`SET` (with `EX` and `PX`) · `GET` · `MGET` · `MSET` · `SETNX` · `APPEND` · `STRLEN` · `GETDEL` ·
`INCR` · `DECR` · `INCRBY` · `DECRBY`

### Keys
`EXISTS` · `DEL` · `TYPE` · `KEYS` · `SCAN` · `TTL` · `PTTL` · `EXPIRE` · `PEXPIRE` · `PERSIST`

### Lists
`RPUSH` · `LPUSH` · `LRANGE` · `LLEN` · `LPOP` · `RPOP` · `LINDEX` · `BLPOP`

### Hashes
`HSET` · `HGET` · `HGETALL` · `HKEYS` · `HVALS` · `HLEN` · `HEXISTS` · `HDEL`

### Sets
`SADD` · `SREM` · `SMEMBERS` · `SISMEMBER` · `SCARD`

### Sorted sets
`ZADD` · `ZREM` · `ZSCORE` · `ZRANK` · `ZCARD` · `ZRANGE` (with `WITHSCORES`)

### Streams
`XADD` · `XRANGE` · `XLEN` · `XREAD` (with `BLOCK`)

### Transactions
`MULTI` · `EXEC` · `DISCARD` · `WATCH` · `UNWATCH`

### Pub/sub
`SUBSCRIBE` · `UNSUBSCRIBE` · `PUBLISH`

### Server
`PING` · `ECHO` · `CONFIG GET` · `INFO` · `DBSIZE` · `FLUSHALL` · `SAVE` · `BGSAVE` · `COMMAND`

### Replication
`REPLCONF` · `PSYNC` · `WAIT`, and `--replicaof "host port"`

## Deliberate shortcuts

These are choices, not oversights. Each is marked in the source with a `ponytail:` comment naming
the ceiling and the upgrade path, so `grep -rn 'ponytail:' src/` finds them all.

| Where | Shortcut | Upgrade path |
|---|---|---|
| `RedisStore` blocking pop | One monitor for every list, so a push wakes all waiters and most go back to sleep | Per-key monitors, if a real workload makes the herd measurable |
| `RedisStore.scan` | Cursor is an index into the sorted keyspace, so it costs a sort per call | Reverse binary bucket iteration, which stays correct across table resizes |
| `RedisStore.snapshotStrings` | Only strings are written to RDB. Lists, hashes, sets, sorted sets and streams are skipped | Listpack and quicklist encodings |
| `RedisStream.range` | Linear scan | Binary search, if a stream ever gets long enough to matter |
| `RdbWriter` | Checksum written as eight zero bytes. Redis skips verification when the checksum is zero | Implement CRC64 with the Jones polynomial |
| `RdbReader` | LZF-compressed strings are refused with a clear message | Implement LZF decompression |
| `CommandDispatcher.wait` | `WAIT` returns the connected replica count immediately | Send `GETACK` and block until enough replicas acknowledge the offset |
| Replication | Full resync every time | A backlog buffer, so a reconnecting replica gets only the missing commands |

Two more behaviours worth knowing, both correct rather than shortcuts. `SCAN` pages can come back
short, because an expired key consumes a step without being returned, so only cursor 0 ends a scan.
And `HGETALL` preserves insertion order here because the store uses `LinkedHashMap`, which real
Redis does not promise, so a client must not rely on it.

## Not implemented

Ordered by how much a reader would learn from building it, rather than by how much production Redis
needs it.

| Feature | Effort | Worth building? |
|---|---|---|
| `EVAL`, Lua scripting | 2 to 3 stages | Yes. The only genuinely new mechanism left. Atomic multi-command execution without `MULTI` |
| Consumer groups: `XGROUP`, `XREADGROUP`, `XACK` | 2 stages | Yes. Turns a stream from a log into a work queue with per-consumer acknowledgement |
| Partial resync | 1 to 2 stages | Moderate. A backlog buffer and offset arithmetic. Real, and narrow |
| AOF persistence | 2 stages | Moderate. Conceptually simpler than RDB, mostly a write-ahead log |
| Blocking `WAIT` | 1 stage | Small. Reuses the `GETACK` path already present |
| Set algebra: `SINTER`, `SUNION`, `SDIFF` | Under a stage | Low. Mechanical, no new ideas |
| `CONFIG SET` | Under a stage | Low. Needs each parameter to have a live effect |
| Non-string types in RDB | 2 to 3 stages | Low. Byte-format work, no new concepts |
| Key eviction policies, `maxmemory` | 1 to 2 stages | Moderate. LRU and LFU approximation is a nice algorithm problem |
| ACLs, TLS | 2+ stages | Low for learning, high for production |
| Cluster mode: hash slots, redirects, gossip | 6+ stages | A different project wearing the same name |

## The process for adding a stage

Each stage in this repository followed the same loop, and the next one should too.

1. **Write the stage guide first**, in `stages/guides/stage-NN-slug.md`. Goal, the concepts to
   understand, the constraints, and the exact verification commands with expected output. Writing
   the verification before the code forces you to decide what "working" means.
2. **Implement it.** Keep the boundaries: nothing outside `RespParser` and `RespWriter` touches
   `\r\n`, and nothing outside `RedisStore` decides whether a key has expired.
3. **Write tests that a single-threaded run cannot pass**, where the stage warrants it. Concurrent
   `INCR`, concurrent `RPUSH`, the `$` cursor in blocking `XREAD`, and
   `everyMutatingCommandInvalidatesAWatch` all exist because the bug they catch is invisible
   otherwise.
4. **Verify against `redis-cli`, not only against your own tests.** Three bugs in this project were
   found only this way: `TTL` rounding down instead of up, a catch on `IllegalArgumentException`
   that never matched `IllegalStateException`, and a test that passed against a real Redis holding
   the port instead of against this server.
5. **Mark any deliberate shortcut** with a `ponytail:` comment naming the ceiling, then add a row to
   the table above.
6. **Update this page.** Move the feature out of "Not implemented", add its commands to the list,
   and adjust the counts at the top.

### If a new write command is added

Add it to the list in `everyMutatingCommandInvalidatesAWatch`. A command that mutates without
bumping the version counter silently breaks `WATCH`, with no error and no crash, in exactly the use
case `WATCH` exists for. That test is the only thing standing between you and that bug.

## Checking the current state

```bash
mvn test                                    # 295 tests
grep -rn 'ponytail:' src/                   # every deliberate shortcut
grep -oE 'case "[A-Z]+"' src/main/java/*/*/*/CommandDispatcher.java | sort -u | wc -l
```
