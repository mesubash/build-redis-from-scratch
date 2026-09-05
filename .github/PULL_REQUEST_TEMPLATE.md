## What this changes

<!-- One or two sentences. -->

## Checklist

- [ ] `mvn test` passes
- [ ] Verified against a real `redis-server` on another port, not only against the test suite
- [ ] Error strings match Redis exactly, including quotes and word order
- [ ] If this adds a write command, it is in `everyMutatingCommandInvalidatesAWatch`
- [ ] Any deliberate shortcut carries a `ponytail:` comment naming its ceiling
- [ ] `ROADMAP.md` updated if this moves a feature out of "not implemented"
- [ ] No new dependencies

## Verification

<!-- Paste the redis-cli session that proves it works. -->

```text

```
