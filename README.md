# ZPP-Replay

ZPP-Replay is a free, open source addon for ZonePractice Pro.
It records matches and lets players watch them back as client-side replays: player and bot movement, equipment,
rounds, block changes, combat effects, projectiles and dropped items.

Replays are stored as compressed `.zrp` files with an index under `plugins/ZPPReplay/replays/`. The file format is
described in [docs/FORMAT.md](docs/FORMAT.md).

## Requirements

- Paper 1.21.11 or 26.x, running Java 25.
- ZonePractice Pro with API level 10 or newer (API 2.11.0+).
- PacketEvents installed as a separate plugin.

## Install

1. Put ZonePractice Pro, PacketEvents and `ZPP-Replay-<version>.jar` in `plugins/`.
2. Start the server once, then edit `plugins/ZPPReplay/config.yml` if needed and restart.

## Record and watch a match

Recording is opt-in per participant. `zppreplay.record` defaults to op, so grant it to other players:

```text
/lp user <player> permission set zppreplay.record true
```

When at least one match participant has `zppreplay.record`, the match is recorded if recording is enabled and the
configured limits allow it. Every participant in a recorded match can see it in their replay list; opted-in
participants also own it for retention purposes. Run `/replay` to open your replay menu and pick an entry.

You can also start a replay by its ID with `/replay play <id>`. A viewer must be in the lobby. The replay uses an
arena leased from ZonePractice Pro, so configure arena copies for the arenas you want to watch. Playback is refused
when no free arena is available.

During playback the hotbar has controls: arrow (back 10 seconds), clock (pause/play), spectral arrow (forward 10
seconds), sugar (cycle playback speed), ender eye (follow the next visible player), feather (noclip: fly through
blocks; double-tap sneak or `/replay noclip` to get your controls back) and barrier (leave).

## Commands and permissions

| Command | Purpose | Permission |
|---|---|---|
| `/replay` or `/replays` | Open your own replay menu | — |
| `/replay play <id>` | Watch a replay | `zppreplay.watch` for a match participant; `zppreplay.watch.others` for anyone else |
| `/replay list <player>` | Open a player's replay menu | `zppreplay.watch.others` (default op) |
| `/replay leave` | Stop watching and return to your saved location | — |

| Permission | Default | Purpose |
|---|---|---|
| `zppreplay.record` | op | Marks a player whose matches are recorded and charged to them. |
| `zppreplay.watch` | true | Watch replays of matches you took part in. |
| `zppreplay.watch.others` | op | Watch and list replays of any player. |
| `zppreplay.limit.<n>` | — | Sets a player's retention to `n` replays (1–1000). The highest valid `n` wins; without it `STORAGE.DEFAULT-PER-PLAYER` applies. |

## Configuration

Edit `plugins/ZPPReplay/config.yml`. Settings are read at startup; restart after changing them.

| Key | Default | Effect |
|---|---:|---|
| `RECORDING.ENABLED` | `true` | Enables recording for opted-in participants. |
| `RECORDING.MAX-CONCURRENT` | `20` | Maximum matches recorded at once (1–256). |
| `RECORDING.MAX-PLAYERS-PER-MATCH` | `16` | Maximum players plus bot bodies in a recorded match (2–64); larger matches are skipped. |
| `RECORDING.MAX-DURATION-MINUTES` | `30` | Maximum saved duration (1–120); recordings end at this limit. |
| `RECORDING.SHOW-DEAD-SPECTATORS` | `false` | Show players who died and now spectate the match. |
| `STORAGE.DEFAULT-PER-PLAYER` | `10` | Retained replays per player unless a `zppreplay.limit.<n>` permission applies (1–1000). |
| `PLAYBACK.MAX-CONCURRENT-VIEWERS` | `10` | Maximum simultaneous playback viewers (1–100). |
| `CONTROLS.<ITEM>` | — | Slot, material and enabled state of the hotbar controls. Names and lore are in `messages.yml`. |

When a player's retention limit is exceeded, that player is removed from older replay entries. A replay file is
deleted only when no owner retains it.

## Building

Requires JDK 25. Gradle downloads the other dependencies (Paper API, ZonePractice Pro API, PacketEvents, org.json,
JUnit) from public repositories.

```bash
./gradlew build
```

The plugin jar is written to `build/libs/ZPP-Replay-<version>.jar`. The build also runs the test suite.

To build against a local checkout of the ZonePractice Pro API, place it next to this folder as `ZPP-Api`; it is
used automatically. Pass `-PlocalApi=false` to use the published artifact instead.

## License

ZPP-Replay is released under the license in [LICENSE](LICENSE).
