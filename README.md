# Aurora

Aurora is a self-hostable, frozen-neon Minecraft minigame network targeting Java Edition 1.21.11. Bed Wars and Ice Boat Racing are the flagship modes; this repository is being rebuilt in reviewable increments.

## Quick start

Requirements: Docker Engine with the Compose plugin, and a Minecraft 1.21.11 client.

```sh
git clone <repository-url>
cd <repository-directory>
docker compose up --build
```

When the server reports that it is ready, connect to `localhost:25565`. The first launch downloads the pinned Minecraft server version and builds AuroraLobby, so it needs internet access. Subsequent starts reuse the local image and server data. Stop with Ctrl+C; persistent runtime data lives in ignored `server-data/`.

To run detached: `docker compose up --build -d`; to stop: `docker compose down`.

## Current status

The repository wipe and initial boot scaffold are complete. `/play bedwars classic|chill|rush` launches an early two-team Bed Wars prototype when at least two players join the same variant. It supports bridge building, bed breaking, basic team respawns, eliminations, and victory/return-to-lobby. Chill Wars cancels direct player-vs-player damage. Ice Boat Racing now has `/play iceboats solo [easy|normal|hard]` and `/play iceboats 1v1 [easy|normal|hard]`: each run randomly selects one of 20 generated oval circuits, builds it in the configured world, and enforces four sequential checkpoints before the finish. The difficulty changes course width. The racing feature is an early fun prototype: no persistent PBs, ghost lines, podiums, boosts, splits, or polished map cleanup yet. Bed Wars still lacks its shop/generators/upgrades; Velocity, persistence, and resource pack are not implemented. `/settings pvp off` blocks PvP Bed Wars queue entry for that session. See `docs/ROADMAP.md`; the full acceptance criteria are not yet met.

## Development

The plugin is a Gradle Java 21 project under `packages/lobby`. Build it with `gradle :lobby:jar`. Paper API is compile-only; Adventure MiniMessage is bundled. The Docker build compiles the plugin and installs it into the server image.

## License

See repository license information when available.
