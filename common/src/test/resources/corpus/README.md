Shared `.linear` test corpus.

Regenerate the binary fixtures with:

```bash
./gradlew generateTestCorpus
```

Structure:

- `files/valid`: standalone valid `.linear` fixtures
- `files/invalid`: truncated, bad-CRC, and bad-signature fixtures
- `files/backups`: standalone valid and corrupt `.linear.bak` fixtures
- `worlds/full-spectrum`: `region/`, `poi/`, and `entities/` examples
- `worlds/recovery-valid-backup`: corrupt main with recoverable backup
- `worlds/recovery-corrupt-backup`: corrupt main and corrupt backup
- `worlds/legacy-backup-migration`: old colocated `.bak`, canonical `backups/`, dedupe, and conflict cases
- `worlds/sync-backups`: refresh, orphan-delete, ignored-corrupted, and missing-backup scenarios
- `worlds/prune-candidates`: safe prune candidates plus chunks that must be retained

The generator intentionally builds fixtures from real `LinearRegionFile` writes, then derives corrupt variants by mutating bytes. That keeps the corpus aligned with the current storage contract while still exercising corruption handling paths.
