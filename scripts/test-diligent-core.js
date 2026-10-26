/**
 * Self-check for Diligent's core extension: the supervisor's decisions (run
 * file discovery, status and exit marker parsing, cause naming, backoff and
 * giving up, the process warning), and one supervised run end to end against
 * a stand-in tmux.
 *
 *   node scripts/test-diligent-core.js
 */

'use strict';

const assert = require('assert');
const fs = require('fs');
const os = require('os');
const path = require('path');
const Module = require('module');
const { execFileSync } = require('child_process');
const { newestExtensionDir } = require('./lib/bundled-extension');

const DIR = newestExtensionDir('diligent.diligent-core-');
const sup = require(path.join(DIR, 'supervisor.js'));

const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'diligent-core-'));
process.on('exit', () => fs.rmSync(tmp, { recursive: true, force: true }));

// ---- supervisor: pure decisions -------------------------------------------

function checkDiscovery() {
    assert.strictEqual(sup.sessionOfRunFile('diligent-my-app-dev.sh'), 'diligent-my-app-dev');
    assert.strictEqual(sup.sessionOfRunFile('supervisor.json'), null);
    assert.strictEqual(sup.sessionOfRunFile('other-app.sh'), null);
    assert.strictEqual(sup.sessionOfRunFile('diligent-a;rm -rf.sh'), null);
    assert.strictEqual(sup.sessionOfRunFile('diligent-A-dev.sh'), null, 'RunSession never makes capitals');
    assert.ok(!sup.isSessionName('diligent-x\n'));
    assert.ok(!sup.isSessionName(42));

    const text = '# diligent-run: {"dir":"/p/My \'App\'","name":"dev\\nx","port":3000}\ncommand -v tmux\n';
    assert.deepStrictEqual(sup.parseRunFile(text), { dir: "/p/My 'App'", name: 'dev\nx', port: 3000 });
    assert.deepStrictEqual(sup.parseRunFile('# diligent-run: {"dir":"/p","name":"dev"}\n'), { dir: '/p', name: 'dev', port: null });
    assert.deepStrictEqual(sup.parseRunFile('# diligent-run: {"dir":"/p","name":"dev","port":70000}\n').port, null);
    assert.strictEqual(sup.parseRunFile('#!/bin/bash\n'), null);
    assert.strictEqual(sup.parseRunFile('# diligent-run: {broken\n'), null);

    const runJson = JSON.stringify({ profiles: [{ name: 'dev', command: 'x', port: 5173 }, { name: 'api', command: 'y', port: '8000' }] });
    assert.strictEqual(sup.portFromRunJson(runJson, 'dev'), 5173);
    assert.strictEqual(sup.portFromRunJson(runJson, 'api'), 8000);
    assert.strictEqual(sup.portFromRunJson(runJson, 'gone'), null);
    assert.strictEqual(sup.portFromRunJson('nope', 'dev'), null);
}

function checkStatusAndMarker() {
    assert.deepStrictEqual(sup.parseStatus('stopped\n'), { kind: 'stopped' });
    assert.deepStrictEqual(sup.parseStatus('exit 137\n'), { kind: 'exit', code: 137 });
    assert.strictEqual(sup.parseStatus(null), null);
    assert.strictEqual(sup.parseStatus('garbage'), null);
    assert.strictEqual(sup.exitMarker(['a', 'DILIGENT_EXIT:3']), 3);
    assert.strictEqual(sup.exitMarker(['DILIGENT_EXIT:1', 'restarted', 'DILIGENT_EXIT:0']), 0);
    assert.strictEqual(sup.exitMarker(['a', 'b']), null);
    assert.strictEqual(sup.exitMarker(['echo DILIGENT_EXIT:3 in a sentence']), null);

    assert.strictEqual(sup.classify({ alive: true, status: { kind: 'stopped' }, runFileAgeMs: 0 }), 'running');
    assert.strictEqual(sup.classify({ alive: false, status: { kind: 'stopped' }, runFileAgeMs: 1e9 }), 'stopped');
    assert.strictEqual(sup.classify({ alive: false, status: { kind: 'exit', code: 0 }, runFileAgeMs: 1e9 }), 'finished');
    assert.strictEqual(sup.classify({ alive: false, status: { kind: 'exit', code: 1 }, runFileAgeMs: 0 }), 'ended');
    assert.strictEqual(sup.classify({ alive: false, status: null, runFileAgeMs: 1000 }), 'starting');
    assert.strictEqual(sup.classify({ alive: false, status: null, runFileAgeMs: 60_000 }), 'ended');
}

function checkCauses() {
    const low = { totalKb: 8_000_000, availableKb: 150_000 };
    const plenty = { totalKb: 8_000_000, availableKb: 4_000_000 };
    const base = { name: 'dev', memory: plenty, processes: 10, log: [], port: 3000 };

    assert.strictEqual(sup.nameCause({ ...base, exitCode: 137, memory: low }).cause, 'low-memory');
    assert.strictEqual(sup.nameCause({ ...base, exitCode: null, memory: { totalKb: 8_000_000, availableKb: 700_000 } }).cause, 'low-memory',
        'under a tenth of MemTotal is low too');
    assert.strictEqual(sup.nameCause({ ...base, exitCode: 137, processes: 30 }).cause, 'process-limit');
    assert.strictEqual(sup.nameCause({ ...base, exitCode: null, processes: 28 }).cause, 'process-limit');
    assert.strictEqual(sup.nameCause({ ...base, exitCode: 1, processes: 31 }).cause, 'exit', 'an ordinary exit is not the limit');

    const busy = sup.nameCause({ ...base, exitCode: 1, log: ['Error: listen EADDRINUSE: address already in use :::3000', 'DILIGENT_EXIT:1'] });
    assert.strictEqual(busy.cause, 'port-busy');
    assert.ok(busy.message.includes('port 3000'), busy.message);

    const killed = sup.nameCause({ ...base, exitCode: null, log: ['ready'] });
    assert.strictEqual(killed.cause, 'killed');
    assert.ok(killed.message.startsWith('"dev" was closed by Android'), killed.message);

    const log = Array.from({ length: 30 }, (_, i) => 'line ' + i).concat(['DILIGENT_EXIT:2']);
    const exit = sup.nameCause({ ...base, exitCode: 2, log });
    assert.strictEqual(exit.cause, 'exit');
    const [first, ...rest] = exit.message.split('\n\nLast lines of its output:\n');
    assert.strictEqual(first, '"dev" stopped with error code 2.');
    assert.deepStrictEqual(rest[0].split('\n'), log.slice(10, 30), 'the last 20 lines, without the marker');

    const restarting = sup.withOutcome(exit.message, 'restarting');
    assert.ok(restarting.startsWith('"dev" stopped with error code 2. Diligent is starting it again.\n'), restarting);
    assert.ok(sup.withOutcome(killed.message, 'failed').includes('stopped restarting it'));

    for (const m of [busy.message, killed.message, exit.message, restarting]) {
        assert.ok(!/[\u2013\u2014\u2015]/.test(m), 'no fancy dashes in messages');
    }
}

function checkBackoff() {
    const now = 100 * 60 * 1000;
    assert.deepStrictEqual(sup.nextRestart([], now), { giveUp: false, delayMs: 1000 });
    assert.deepStrictEqual(sup.nextRestart([now - 1000], now), { giveUp: false, delayMs: 5000 });
    assert.deepStrictEqual(sup.nextRestart([now - 2000, now - 1000], now), { giveUp: false, delayMs: 30000 });
    assert.deepStrictEqual(sup.nextRestart([1, 2, 3, 4].map((i) => now - i * 1000), now), { giveUp: false, delayMs: 30000 });
    assert.deepStrictEqual(sup.nextRestart([1, 2, 3, 4, 5].map((i) => now - i * 1000), now), { giveUp: true });
    // Restarts older than ten minutes no longer count.
    const old = [1, 2, 3, 4, 5].map((i) => now - sup.RESTART_WINDOW_MS - i * 1000);
    assert.deepStrictEqual(sup.nextRestart(old, now), { giveUp: false, delayMs: 1000 });
    assert.deepStrictEqual(sup.nextRestart([...old, now - 5000], now), { giveUp: false, delayMs: 5000 });
}

function checkProcesses() {
    const now = Date.now();
    assert.strictEqual(sup.processCount(JSON.stringify({ timestamp: now - 5000, total: 12 }), now), 12);
    assert.strictEqual(sup.processCount(JSON.stringify({ timestamp: now - 60_000, total: 12 }), now), null, 'stale');
    assert.strictEqual(sup.processCount(JSON.stringify({ total: 12 }), now), null, 'no time is stale');
    assert.strictEqual(sup.processCount('{', now), null);
    assert.strictEqual(sup.processCount(null, now), null);

    assert.ok(sup.crossedProcessWarning(23, 24));
    assert.ok(sup.crossedProcessWarning(null, 25));
    assert.ok(!sup.crossedProcessWarning(24, 26), 'once per crossing');
    assert.ok(!sup.crossedProcessWarning(30, 20));
    assert.ok(sup.crossedProcessWarning(20, 24), 'again after falling below');
    assert.ok(!sup.crossedProcessWarning(20, null));

    // The monitor's own file name and limit, so the two cannot drift apart.
    const monitor = fs.readFileSync(path.join(__dirname, '../android/app/src/main/assets/process-monitor.js'), 'utf8');
    assert.ok(/const HARD_LIMIT = 32;/.test(monitor), 'process-monitor.js HARD_LIMIT changed');
    assert.strictEqual(sup.PROCESS_LIMIT, 32);
    assert.ok(fs.readFileSync(path.join(DIR, 'extension.js'), 'utf8').includes("'vscodroid-processes.json'"));
    const ext = newestExtensionDir('vscodroid.vscodroid-process-monitor-');
    assert.ok(fs.readFileSync(path.join(ext, 'extension.js'), 'utf8').includes("'vscodroid-processes.json'"),
        'the process monitor extension reads a different snapshot file now');

    const payload = JSON.parse(sup.updatePayload([{ id: 'diligent-a-dev', name: 'dev', project: 'a', state: 'running' }], 9));
    assert.deepStrictEqual(payload, {
        tasks: [{ id: 'diligent-a-dev', name: 'dev', project: 'a', state: 'running', cause: null, message: null, restarts: 0 }],
        processes: 9,
        processLimit: 32,
    });
    assert.strictEqual(JSON.parse(sup.updatePayload([], null)).processes, null);
}

// ---- supervisor: one run, end to end ---------------------------------------

/**
 * A tmux that keeps its sessions in a file: enough for list-sessions,
 * has-session, send-keys, kill-session, and a new-session that records the
 * name without running anything.
 */
function fakeTmux(bin, state) {
    fs.mkdirSync(bin, { recursive: true });
    const file = path.join(bin, 'tmux');
    fs.writeFileSync(file, [
        '#!/bin/bash',
        `S=${JSON.stringify(state)}`,
        'touch "$S"',
        'sub=$1; shift',
        'case "$sub" in',
        '  list-sessions) if [ -s "$S" ]; then cat "$S"; else echo "no server running on /tmp/x" >&2; exit 1; fi ;;',
        '  has-session) grep -qx "${2#=}" "$S" ;;',
        '  send-keys) exit 0 ;;',
        '  kill-session) grep -vx "${2#=}" "$S" > "$S.n"; mv "$S.n" "$S" ;;',
        '  new-session) echo "$3" >> "$S" ;;',
        'esac',
        '',
    ].join('\n'));
    fs.chmodSync(file, 0o755);
}

async function checkSupervisedRun() {
    const home = path.join(tmp, 'home');
    const bin = path.join(tmp, 'bin');
    const sessions = path.join(tmp, 'sessions');
    fakeTmux(bin, sessions);
    process.env.HOME = home;
    process.env.TMPDIR = path.join(tmp, 'tmpdir');
    process.env.PATH = bin + path.delimiter + process.env.PATH;
    fs.mkdirSync(process.env.TMPDIR, { recursive: true });

    const sent = [];
    const warnings = [];
    const registered = new Map();
    const vscodeStub = {
        l10n: { t: (s, ...a) => s.replace(/\{(\d+)\}/g, (_, i) => a[i]) },
        window: { showWarningMessage: (m) => { warnings.push(m); return Promise.resolve(); } },
        workspace: { onDidSaveTextDocument: () => ({ dispose() {} }), workspaceFolders: undefined },
        commands: {
            executeCommand: (cmd, ...args) => { if (cmd === 'diligent.bridge.updateTasks') sent.push(JSON.parse(args[0])); return Promise.resolve(); },
            registerCommand: (id, fn) => { registered.set(id, fn); return { dispose() {} }; },
        },
    };
    const resolveFilename = Module._resolveFilename;
    Module._resolveFilename = function (request, ...rest) {
        return request === 'vscode' ? 'vscode' : resolveFilename.call(this, request, ...rest);
    };
    require.cache.vscode = { id: 'vscode', filename: 'vscode', loaded: true, exports: vscodeStub };
    const core = require(path.join(DIR, 'extension.js'));

    // Registration, then nothing left running.
    const subscriptions = [];
    core.activate({ subscriptions, globalState: { get: () => undefined, update: async () => {} } });
    for (const id of ['diligent.core.stopTask']) {
        assert.ok(registered.has(id), id + ' was not registered');
    }
    subscriptions.forEach((s) => s.dispose());
    await new Promise((r) => setTimeout(r, 300));
    sent.length = 0;

    // A run that ended with an error: its log, status and launch script.
    const id = 'diligent-shop-dev';
    const project = path.join(tmp, "shop 'x'");
    const runs = path.join(home, '.diligent', 'runs');
    const logs = path.join(home, '.diligent', 'logs');
    fs.mkdirSync(runs, { recursive: true });
    fs.mkdirSync(logs, { recursive: true });
    fs.mkdirSync(path.join(project, '.diligent'), { recursive: true });
    fs.writeFileSync(path.join(project, '.diligent', 'run.json'), JSON.stringify({ profiles: [{ name: 'dev', command: 'x' }] }));
    const runFile = path.join(runs, id + '.sh');
    fs.writeFileSync(runFile, [
        '# diligent-run: ' + JSON.stringify({ dir: project, name: 'dev' }),
        'if tmux has-session -t \'=' + id + '\' 2>/dev/null; then exit 0; fi',
        'rm -f ' + JSON.stringify(path.join(logs, id + '.status')),
        'echo started >> ' + JSON.stringify(path.join(tmp, 'starts')),
        'exec tmux new-session -d -s \'' + id + '\'',
        '',
    ].join('\n'));
    const old = new Date(Date.now() - 60_000);
    fs.utimesSync(runFile, old, old);
    fs.writeFileSync(path.join(logs, id + '.status'), 'exit 1\n');
    fs.writeFileSync(path.join(logs, id + '.log'), '[diligent] $ x\nboom\nDILIGENT_EXIT:1\n');

    const supervisor = new core.Supervisor();
    await supervisor.check();
    let last = sent[sent.length - 1];
    assert.strictEqual(last.tasks.length, 1);
    assert.strictEqual(last.tasks[0].state, 'restarting');
    assert.strictEqual(last.tasks[0].cause, 'exit');
    assert.strictEqual(last.tasks[0].project, "shop 'x'");
    assert.ok(last.tasks[0].message.includes('boom'), last.tasks[0].message);

    // A check while the restart waits changes nothing and starts nothing.
    await supervisor.check();
    assert.ok(!fs.existsSync(path.join(tmp, 'starts')));

    await new Promise((r) => setTimeout(r, 1300));
    assert.strictEqual(fs.readFileSync(path.join(tmp, 'starts'), 'utf8'), 'started\n', 'restarted once, by its launch script');
    await supervisor.check();
    last = sent[sent.length - 1];
    assert.strictEqual(last.tasks[0].state, 'running');
    assert.strictEqual(last.tasks[0].restarts, 1);

    // A new supervisor (the editor server restarted) finds it running and starts nothing.
    const again = new core.Supervisor();
    await again.check();
    assert.strictEqual(fs.readFileSync(path.join(tmp, 'starts'), 'utf8'), 'started\n');
    assert.strictEqual(again.tasks.get(id).restarts, 1, 'the restart count survives in supervisor.json');

    // Killed: the session vanished, no marker in the log.
    fs.writeFileSync(sessions, '');
    fs.writeFileSync(path.join(logs, id + '.log'), '[diligent] $ x\nready\n');
    await again.check();
    last = sent[sent.length - 1];
    assert.strictEqual(last.tasks[0].cause, 'killed');
    assert.strictEqual(last.tasks[0].state, 'restarting');

    // Stop: never restarted, the run file is gone, the app hears the list is empty.
    await again.stop(id);
    assert.strictEqual(fs.readFileSync(path.join(logs, id + '.status'), 'utf8'), 'stopped\n');
    assert.ok(!fs.existsSync(runFile));
    assert.deepStrictEqual(sent[sent.length - 1].tasks, []);
    await new Promise((r) => setTimeout(r, 6000 + 300));
    assert.strictEqual(fs.readFileSync(path.join(tmp, 'starts'), 'utf8'), 'started\n', 'a stopped run was restarted');
    await assert.rejects(again.stop('diligent-x; rm -rf /'));

    // Five restarts within ten minutes: given up, and it stays given up.
    fs.writeFileSync(runFile, '# diligent-run: ' + JSON.stringify({ dir: project, name: 'dev' }) + '\nexit 0\n');
    fs.utimesSync(runFile, old, old);
    fs.writeFileSync(path.join(logs, id + '.status'), 'exit 1\n');
    const now = Date.now();
    fs.writeFileSync(path.join(runs, 'supervisor.json'), JSON.stringify({ [id]: { history: [1, 2, 3, 4, 5].map((i) => now - i * 1000), failed: null } }));
    const third = new core.Supervisor();
    await third.check();
    last = sent[sent.length - 1];
    assert.strictEqual(last.tasks[0].state, 'failed');
    assert.ok(last.tasks[0].message.includes('stopped restarting it'), last.tasks[0].message);
    await new core.Supervisor().check();
    assert.strictEqual(sent[sent.length - 1].tasks[0].state, 'failed');

    // Process warning once per crossing.
    fs.writeFileSync(path.join(process.env.TMPDIR, 'vscodroid-processes.json'), JSON.stringify({ timestamp: Date.now(), total: 25 }));
    await third.check();
    await third.check();
    assert.strictEqual(warnings.length, 1, warnings.join('\n'));
    assert.strictEqual(sent[sent.length - 1].processes, 25);
    third.dispose();
}

async function main() {
    checkDiscovery();
    checkStatusAndMarker();
    checkCauses();
    checkBackoff();
    checkProcesses();
    await checkSupervisedRun();
    console.log('diligent core: discovery, causes, backoff, process budget and a supervised run OK');
    process.exit(0);
}

main().catch((err) => {
    console.error(err);
    process.exit(1);
});
