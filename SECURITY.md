# Security

## Scope

This is a teaching project. It has no authentication, no TLS, no ACLs, and no protection against a
hostile client beyond a few length caps in the RESP parser. **Do not run it on a public network or
put real data in it.**

Bind it to loopback and treat it as a local toy.

## Known limits, by design

- No `AUTH`, no ACLs. Anyone who can reach the port has full access.
- No TLS. Everything is plaintext.
- No `maxmemory` or eviction. A client can grow the keyspace until the JVM dies.
- `KEYS` and `SCAN` both walk the whole keyspace and are O(n).
- The RESP parser caps bulk strings at 512 MB and arrays at 1M elements. Those are the only limits
  on what one client can make the server allocate.

## Reporting

If you find something genuinely exploitable that is not on the list above, open an issue describing
the class of problem. Do not include a working exploit.
