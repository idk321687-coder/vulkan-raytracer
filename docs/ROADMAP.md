# Aurora implementation roadmap

This is the incremental build plan; only the first bootstrap slice is present today.

1. Repository wipe, Paper Compose boot, and joinable lobby foundation.
2. Hub presentation, `/play` menu, queue lifecycle, and transfer/routing. The current menu and local queue-selection UI are an early partial slice; they do not launch matches.
3. Bed Wars match prototype: current implementation supports two-team Classic/Chill/Rush queue starts, bridging, beds, respawns, eliminations, and match-end return. Next work: isolated arena lifecycle, complete format/team setup, and tests.
4. Bed Wars shop, generators, Chill Wars (PvP disabled), and Rush.
5. Ice Boat Racing: early solo and 1v1 modes now randomly choose from 20 generated oval circuits, with easy/normal/hard course widths and ordered checkpoints. Still needed: genuinely distinct course layouts, personal-best persistence, lap/split HUD, ghosts, boosts, podium, and tests.
6. Supporting modes and shared PvP preference enforcement.
7. Generated resource pack and visual/audio pass.
8. Persistence, progression, parties, and social features.
9. Admin/docs, automated integration tests, and load test.

Acceptance criteria from the product brief are not claimed until implemented and verified. In particular, the first slice is single-server; Velocity/multi-instance routing remains a later milestone rather than a misleading empty proxy service.
