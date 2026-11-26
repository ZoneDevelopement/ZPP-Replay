# live-profile

Runs the recorder on a real Paper 1.21.11 server with Mineflayer bots (sword matches, BuildUHC with block churn, both at once) and prints the `REPLAY_PROFILE` and `REPLAY_SAVED` lines plus a summary.
Env: `ZPP_JAR` (core jar, e.g. `ZonePractice Pro/distribution/build/libs/ZonePracticePro-<version>.jar`), `REPLAY_JAR` (ZPP-Replay jar), optional `ZPP_PACKETEVENTS` (default: core `gradle/libs.versions.toml`).
Needs the core e2e `node_modules` (`npm ci` in `ZonePractice Pro/e2e`); the server runs in `$TMPDIR/zpp-replay-profile` with `-Dzpp.replay.profile=true`.
Command: `ZPP_JAR=<core jar> REPLAY_JAR=build/libs/ZPP-Replay-<version>.jar node tools/live-profile/run.mjs`
Optional `REPLAY_JFR=/abs/path.jfr` adds `-XX:StartFlightRecording=filename=<path>,settings=profile,dumponexit=true` and `-XX:FlightRecorderOptions=stackdepth=128` to the server JVM (off by default); the recording is written when the server exits.
Copies the core e2e template and cached downloads into the temp root, so the core e2e folder is never written.
