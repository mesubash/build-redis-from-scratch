# Contributing

Thanks for looking. This is a learning project first and a Redis server second, so the bar for a
change is slightly unusual: it should teach something, or make something already here clearer.

## What is welcome

**Bug fixes.** Especially anything where this server disagrees with real `redis-server`. Those are
the most valuable reports, because the whole point is behavioural fidelity.

**New commands**, if they follow the process below. See [ROADMAP.md](./ROADMAP.md) for what is
missing and what each piece is worth.

**Clearer explanations.** The stage guides and the comments matter as much as the code.

**Tests that catch something a single-threaded run cannot.** Concurrency, protocol edge cases,
byte-level framing.

## What is not welcome

**Dependencies.** The project uses plain Java and JUnit. No Spring, no Netty, no Redis client
library. Understanding what those hide is the point.

**Premature abstraction.** No interface with one implementation, no factory for one product. A class
appears when a stage makes its absence painful, not before.

**Performance work without a measurement.** Several deliberate shortcuts are documented in
[ROADMAP.md](./ROADMAP.md) with their ceilings. If you want to lift one, show the number first.

## The process for a new command

1. **Read the roadmap** to see whether the command is listed and what it is grouped with.
2. **Write the test first**, or at least decide what the verification looks like before you code.
   State the exact `redis-cli` session that should work.
3. **Keep the boundaries.** Nothing outside `RespParser` and `RespWriter` touches `\r\n`. Nothing
   outside `RedisStore` decides whether a key has expired. Command meaning lives in
   `CommandDispatcher` and knows nothing about sockets.
4. **Match Redis exactly on error strings.** Clients and test suites match on them. Copy the wording,
   the quotes, and the word order.
5. **Verify against real `redis-server`, not only against your own tests.** Run it on another port
   and compare. Three bugs in this project were found only that way.
6. **If your command writes**, add it to `everyMutatingCommandInvalidatesAWatch` in `WatchTest`. A
   command that mutates without bumping the version counter breaks `WATCH` silently.
7. **Mark any deliberate shortcut** with a `ponytail:` comment naming the ceiling and the upgrade
   path, then add a row to the roadmap table.
8. **Update the roadmap** counts and move the command out of the "not implemented" list.

## Running things

```bash
mvn test                                  # 295 tests
mvn -q clean compile
java -cp target/classes com.example.redis.Main --port 6399

redis-cli -p 6399 ping                    # verify against the real client
```

Port 6379 is often taken by a real Redis or a Docker container. Check before you trust a result:

```bash
lsof -nP -iTCP:6379 -sTCP:LISTEN          # empty output means free
```

## Code style

Match what is there. Sparse `//` comments explaining *why*, never restating the line. No Javadoc
blocks. Java 21 language level, so records, pattern matching in `switch`, and virtual threads are all
fair game.

## Reporting a behavioural difference

The most useful bug report shows both servers side by side:

```text
$ redis-cli -p 6399 ttl k     # this project
(integer) 4
$ redis-cli -p 6380 ttl k     # real redis
(integer) 5
```

That is exactly how the `TTL` rounding bug was found.
