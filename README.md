# Aurora

Aurora is a self-hostable, frozen-neon Minecraft minigame network targeting Java Edition 1.21.11. Bed Wars and Ice Boat Racing are the flagship modes; the implementation is being built in reviewable increments.

## Run locally on Windows (no Docker)

Requirements: Windows PowerShell, a **Java 21 JDK** on `PATH`, and a Minecraft 1.21.11 client. From the repository, double-click `scripts\\run.bat` or run it from Command Prompt/PowerShell. On first run the script downloads Gradle and the latest Paper build for 1.21.11, builds the plugin, and prepares `server-local\\`. It will ask you to review and accept the Minecraft EULA before starting.

Connect to `localhost:25565`. Stop the server with Ctrl+C. Run `scripts\\run.bat` again for subsequent starts; the Paper jar, downloaded Gradle, plugin output, and world data are retained locally. Generated files are ignored by Git.

If startup reports that Java is missing or the version is not 21, install a Java 21 JDK (for example, Temurin 21), make sure `java.exe` is on `PATH`, then open a fresh terminal. The first setup requires internet access; after Gradle and server dependencies have been downloaded, future starts use the local caches.

## Optional Docker path

Docker Compose remains available for users who prefer containers:

```sh
docker compose up --build
```

When the server reports that it is ready, connect to `localhost:25565`. Stop with Ctrl+C; server files are stored in the ignored `server-data/` directory. Detached mode: `docker compose up --build -d`; stop it with `docker compose down`.

## Current status

`/play bedwars classic|chill|rush` launches an early two-team Bed Wars prototype when at least two players join the same variant. It supports bridge building, bed breaking, basic team respawns, eliminations, and victory/return-to-lobby. Chill Wars cancels direct player-vs-player damage. Ice Boat Racing has `/play iceboats solo [easy|normal|hard]` and `/play iceboats 1v1 [easy|normal|hard]`: each run randomly selects one of 20 generated oval circuits, builds it in the configured world, and enforces four sequential checkpoints before the finish. Difficulty changes course width. Racing remains an early prototype: persistent personal bests, ghost lines, podiums, boosts, splits, and polished map cleanup are not implemented. Bed Wars still lacks its shop, generators, and upgrades; Velocity, progression persistence, and the custom resource pack are also not implemented. `/settings pvp off` blocks PvP Bed Wars queue entry for that session. See `docs/ROADMAP.md`; the full acceptance criteria are not yet met.

## Development

The plugin is a Gradle Java 21 project under `packages/lobby`. Build it with `gradle :lobby:jar`. Paper API and Adventure MiniMessage are compile-only. `scripts/start-local.ps1` is the native Windows bootstrap; `scripts/run.sh` is the Docker Compose launcher for Linux/macOS.

## License

See repository license information when available.
