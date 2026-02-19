# FoliaEspGuard

A Folia-compatible anti-ESP plugin for Minecraft 1.21.x.

## What it does

- Hides configured blocks and block entities when a player is farther than a configurable chunk radius.
- Restores the real block data once players move close enough.
- Default radius is **2 chunks**.

## Build

```bash
./gradlew jar
```

(If you don't use wrapper, run `gradle jar`.)

## Config highlights

- `radius-chunks`: default `2`
- `hide-all-block-entities`: default `true`
- `hide-storage-blocks`: default `true`
- `extra-blocks`: includes torches, lanterns, ore-value blocks, anvils, and workstation/table blocks by default.
