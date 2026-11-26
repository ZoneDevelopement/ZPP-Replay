// Live recorder profile: real Paper 1.21.11 server, core + ZPP-Replay jar, Mineflayer bots.
// Usage: ZPP_JAR=... REPLAY_JAR=... node tools/live-profile/run.mjs   (ZPP_PACKETEVENTS defaults to the core toml)
import { spawn } from 'node:child_process';
import { existsSync, readFileSync } from 'node:fs';
import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import readline from 'node:readline';
import { fileURLToPath } from 'node:url';
import { Server } from '../../../ZonePractice%20Pro/e2e/lib/server.js';
import { joinBot, inArena, inLobby, fightUntilDeath, quitAll } from '../../../ZonePractice%20Pro/e2e/lib/bot.js';
import { sleep, waitFor } from '../../../ZonePractice%20Pro/e2e/lib/util.js';

const here = path.dirname(fileURLToPath(import.meta.url));
const core = path.resolve(here, '../../../ZonePractice Pro');
const coreE2e = path.join(core, 'e2e');
const JVM_FLAGS = ['-Dzpp.replay.profile=true'];
// REPLAY_JFR=/abs/path.jfr records a Java Flight Recorder file of the server JVM (off by default).
const jfrFile = process.env.REPLAY_JFR;
if (jfrFile && !path.isAbsolute(jfrFile)) {
  console.error('Set REPLAY_JFR to an absolute path.');
  process.exit(2);
}
const JFR_FLAGS = jfrFile
  ? [`-XX:StartFlightRecording=filename=${jfrFile},settings=profile,dumponexit=true`, '-XX:FlightRecorderOptions=stackdepth=128']
  : [];
const SWORD = ['E2ERpSA', 'E2ERpSB'];
const BUILD = ['E2ERpBA', 'E2ERpBB'];
const WORLD = [
  'execute in minecraft:arenas run forceload add 0 0 30 30',
  'execute in minecraft:arenas run fill 0 60 0 30 60 30 minecraft:smooth_stone',
  'execute in minecraft:arenas run forceload add 40 0 70 30',
  'execute in minecraft:arenas run fill 40 60 0 70 60 30 minecraft:smooth_stone',
  'execute in minecraft:arenas run forceload add 100 0 120 20',
  'execute in minecraft:arenas run fill 100 60 0 120 60 20 minecraft:water',
  'execute in minecraft:arenas run fill 106 60 6 114 60 14 minecraft:smooth_stone',
  'execute in minecraft:arenas run forceload add 140 0 170 30',
  'execute in minecraft:arenas run fill 140 60 0 170 60 30 minecraft:smooth_stone',
];

function required(name) {
  const value = process.env[name];
  if (!value || !existsSync(value)) {
    console.error(`Set ${name} to an existing jar path.`);
    process.exit(2);
  }
  return value;
}

const pluginJar = required('ZPP_JAR');
const replayJar = required('REPLAY_JAR');
const packetEventsVersion = process.env.ZPP_PACKETEVENTS
  ?? readFileSync(path.join(core, 'gradle/libs.versions.toml'), 'utf8').match(/^packetevents\s*=\s*"([^"]+)"/m)?.[1];
if (!packetEventsVersion) {
  console.error('Set ZPP_PACKETEVENTS (no packetevents version in the core libs.versions.toml).');
  process.exit(2);
}

// Server.start hardcodes its java args, so this copy adds the replay profile flag. Same ready line and stop as the base.
class ProfileServer extends Server {
  start(timeoutMs = 240000) {
    return new Promise((resolve, reject) => {
      let timer;
      let settled = false;
      const finish = (error) => {
        if (settled) return;
        settled = true;
        clearTimeout(timer);
        if (error) reject(error);
        else resolve();
      };
      const fail = (error) => {
        if (!settled && this.process.exitCode === null && this.process.signalCode === null) this.process.kill('SIGKILL');
        finish(error);
      };

      this.process = spawn('java', ['-Xms1G', '-Xmx2G', ...JVM_FLAGS, ...JFR_FLAGS, '-jar', 'paper.jar', '--nogui'], { cwd: this.dir, stdio: 'pipe' });
      timer = setTimeout(() => fail(new Error(`Server not ready after ${timeoutMs} ms`)), timeoutMs);

      const onLine = (line) => {
        this.output.push(line);
        if (line.includes('ZonePractice Pro is ready.')) finish();
      };
      for (const stream of [this.process.stdout, this.process.stderr]) {
        readline.createInterface({ input: stream }).on('line', onLine);
      }
      this.process.on('error', fail);
      this.process.on('exit', (code) => finish(new Error(`Server exited with code ${code} before it was ready`)));
    });
  }
}

async function waitForLine(server, regex, timeoutMs) {
  if (server.output.some((line) => regex.test(line))) return;
  await server.waitForOutput(regex, timeoutMs);
}

async function withBots(names, fn) {
  const bots = [];
  try {
    for (const name of names) bots.push(await joinBot(name));
    await sleep(2000);
    return await fn(...bots);
  } finally {
    quitAll(...bots);
  }
}

// Queue, wait for the arena, fight until death, back in the lobby. Returns ms from queue to arena.
async function sword(a, b, label) {
  a.chat('/unranked Sword');
  b.chat('/unranked Sword');
  const queued = Date.now();
  await waitFor(() => inArena(a) && inArena(b), 60000, `${label}: both bots in the arena`);
  const waited = Date.now() - queued;
  await sleep(6000);
  await fightUntilDeath(a, b, 60000);
  await waitFor(() => inLobby(a) && inLobby(b), 30000, `${label}: bots back in the lobby`);
  return waited;
}

// Digs and places blocks around the bot (never the column it stands on) for ms; failures are ignored.
async function churn(bot, ms) {
  const Vec3 = bot.entity.position.constructor;
  const end = Date.now() + ms;
  let digs = 0;
  let places = 0;
  while (Date.now() < end) {
    const base = bot.entity.position.floored();
    const dx = Math.floor(Math.random() * 3) - 1;
    const dz = Math.floor(Math.random() * 3) - 1;
    const dy = Math.floor(Math.random() * 2) - 1;
    if (dx === 0 && dz === 0) {
      await sleep(250);
      continue;
    }
    const cell = base.offset(dx, dy, dz);
    try {
      const target = bot.blockAt(cell);
      if (target && target.boundingBox === 'block' && bot.canDigBlock(target)) {
        await bot.dig(target);
        digs++;
      }
    } catch {}
    try {
      const block = bot.inventory.items().find((item) => /(_planks|cobblestone|_wool|_concrete)$/.test(item.name));
      const reference = bot.blockAt(cell.offset(0, -1, 0));
      if (block && reference && reference.boundingBox === 'block') {
        await bot.equip(block, 'hand');
        await bot.placeBlock(reference, new Vec3(0, 1, 0));
        places++;
      }
    } catch {}
    await sleep(250);
  }
  return { digs, places };
}

async function build(a, b, label) {
  a.chat('/unranked BuildUHC');
  b.chat('/unranked BuildUHC');
  await waitFor(() => inArena(a) && inArena(b), 60000, `${label}: both bots in the build arena`);
  await sleep(6000);
  const { digs, places } = await churn(a, 30000);
  console.log(`  ${label}: churn digs=${digs} places=${places}`);
  await fightUntilDeath(a, b, 150000);
  await waitFor(() => inLobby(a) && inLobby(b), 30000, `${label}: bots back in the lobby`);
}

// Copies the core e2e template and cached downloads into a temp root, so nothing is written under the core e2e folder.
async function setupRoot(root) {
  await fs.mkdir(path.join(root, '.cache'), { recursive: true });
  await fs.rm(path.join(root, 'template'), { recursive: true, force: true });
  await fs.cp(path.join(coreE2e, 'template'), path.join(root, 'template'), { recursive: true });
  for (const file of await fs.readdir(path.join(coreE2e, '.cache')).catch(() => [])) {
    await fs.copyFile(path.join(coreE2e, '.cache', file), path.join(root, '.cache', file));
  }
}

const root = path.join(os.tmpdir(), 'zpp-replay-profile');
const server = new ProfileServer(root);
const results = [];

async function scenario(name, fn) {
  const started = Date.now();
  try {
    await fn();
    results.push({ name, ok: true });
    console.log(`PASS ${name} (${Date.now() - started} ms)`);
  } catch (error) {
    results.push({ name, ok: false });
    console.log(`FAIL ${name}\n  ${error.stack ?? error}`);
  }
  await sleep(3000);
}

try {
  await setupRoot(root);
  await server.prepare({ pluginJar, packetEventsVersion, ops: [...SWORD, ...BUILD] });
  await fs.copyFile(replayJar, path.join(server.dir, 'plugins', 'ZPP-Replay.jar'));
  console.log('Starting Paper 1.21.11 with -Dzpp.replay.profile=true ...');
  await server.start();
  await waitForLine(server, /Enabling ZPPReplay/, 60000);
  await sleep(3000);
  for (const cmd of WORLD) server.command(cmd);
  await sleep(2000);

  for (let i = 1; i <= 3; i++) {
    await scenario(`A${i}: sword match`, () => withBots(SWORD, async (a, b) => {
      console.log(`  A${i}: entered arena after ${await sword(a, b, `A${i}`)} ms`);
    }));
  }
  await scenario('B: BuildUHC with block churn', () => withBots(BUILD, (a, b) => build(a, b, 'B')));
  await scenario('C: sword and BuildUHC at the same time', () => withBots([...SWORD, ...BUILD], async (sa, sb, ba, bb) => {
    const started = Date.now();
    await Promise.all([sword(sa, sb, 'C sword'), build(ba, bb, 'C build')]);
    console.log(`  C: both matches finished in ${Date.now() - started} ms`);
  }));
} catch (error) {
  console.log(`Harness error: ${error.stack ?? error}`);
  results.push({ name: 'setup', ok: false });
} finally {
  await server.stop();
}

const replayLines = server.output.filter((line) => /REPLAY_(PROFILE|PHASES|SAVED)/.test(line));
const profiles = replayLines.filter((line) => line.includes('REPLAY_PROFILE'));
const saved = replayLines.filter((line) => line.includes('REPLAY_SAVED'));
const field = (line, key) => Number(new RegExp(`\\b${key}=([0-9.]+)`).exec(line)?.[1]);
const max = (values) => (values.length ? Math.max(...values) : NaN);

console.log('\n--- REPLAY lines ---');
for (const line of replayLines) console.log(line);

console.log('\n--- summary ---');
console.log(`scenarios: ${results.filter((r) => r.ok).length}/${results.length} passed`);
if (profiles.length) {
  const nsPerActor = profiles.map((line) => field(line, 'nsPerActorTick'));
  const blockEvents = profiles.reduce((sum, line) => sum + field(line, 'blockEvents'), 0);
  const blockNs = profiles.reduce((sum, line) => sum + field(line, 'blockEvents') * field(line, 'blockAvgNs'), 0);
  console.log(`REPLAY_PROFILE windows: ${profiles.length}`);
  console.log(`max tickP99Us: ${max(profiles.map((line) => field(line, 'tickP99Us')))}`);
  console.log(`max tickMaxUs: ${max(profiles.map((line) => field(line, 'tickMaxUs')))}`);
  console.log(`nsPerActorTick range: ${Math.min(...nsPerActor)}..${max(nsPerActor)}`);
  console.log(`total blockEvents: ${blockEvents}, blockAvgNs: ${blockEvents ? Math.round(blockNs / blockEvents) : 0}`);
  console.log(`worst mspt: ${max(profiles.map((line) => field(line, 'mspt'))).toFixed(2)}`);
} else {
  console.log('no REPLAY_PROFILE lines (no recording ticked?)');
}
if (saved.length) {
  const bytes = saved.map((line) => field(line, 'bytes'));
  console.log(`saved replays: ${saved.length}, bytes per replay avg ${Math.round(bytes.reduce((s, b) => s + b, 0) / bytes.length)} min ${Math.min(...bytes)} max ${max(bytes)}`);
} else {
  console.log('saved replays: 0');
}
const errors = server.pluginErrors();
console.log(`pluginErrors(): ${errors.length ? '\n' + errors.join('\n') : '[]'}`);
process.exitCode = results.every((r) => r.ok) && errors.length === 0 ? 0 : 1;
