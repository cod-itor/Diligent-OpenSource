/**
 * Self-check for Diligent's kit extension: every recipe file, the checks made
 * before step 1, step planning, project names, the `kit` command's output,
 * the socket protocol against a real Unix socket, and web-basic and
 * python-script run end to end in a temporary HOME when python3 is there.
 *
 *   node scripts/test-diligent-kit.js
 */

'use strict';

const assert = require('assert');
const fs = require('fs');
const os = require('os');
const path = require('path');
const net = require('net');
const { execFileSync } = require('child_process');
const { newestExtensionDir } = require('./lib/bundled-extension');

const DIR = newestExtensionDir('diligent.diligent-kit-');
const engine = require(path.join(DIR, 'engine.js'));
const server = require(path.join(DIR, 'server.js'));
const { createService } = require(path.join(DIR, 'service.js'));
const kit = require(path.join(DIR, 'kit.js'));

const ROOT = path.resolve(__dirname, '..');
const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'diligent-kit-'));
process.on('exit', () => fs.rmSync(tmp, { recursive: true, force: true }));

const RECIPE_IDS = ['web-basic', 'react-vite', 'nextjs', 'node-api', 'python-script', 'python-data', 'java', 'c-cpp', 'ruby'];

// ---- recipes ---------------------------------------------------------------

function checkRecipes() {
    const { recipes, problems } = engine.loadRecipes(DIR);
    assert.deepStrictEqual(problems, [], 'every recipe file is valid');
    assert.deepStrictEqual(recipes.map((r) => r.id).sort(), [...RECIPE_IDS].sort());
    const byId = Object.fromEntries(recipes.map((r) => [r.id, r]));

    // The guide's table: which recipes go online.
    for (const id of ['web-basic', 'python-script']) assert.strictEqual(byId[id].needs.internet, 'no', id);
    for (const id of ['react-vite', 'nextjs', 'node-api', 'python-data']) assert.strictEqual(byId[id].needs.internet, 'once', id);
    for (const id of ['java', 'c-cpp', 'ruby']) assert.strictEqual(byId[id].needs.internet, 'pack', id);
    for (const r of recipes) for (const s of r.steps) assert.ok(typeof s.title === 'string' && s.title.trim(), r.id + ': every step has a title');
    assert.deepStrictEqual(byId.java.needs.toolchains, ['toolchain_java']);
    assert.deepStrictEqual(byId['c-cpp'].needs.toolchains, ['toolchain_cpp']);
    assert.deepStrictEqual(byId.ruby.needs.toolchains, ['toolchain_ruby']);
    const commands = (/** @type {any} */ r) => r.steps.map((/** @type {any} */ s) => s.command || (s.profiles || []).map((/** @type {any} */ p) => p.command).join(' ')).join('\n');
    assert.ok(/vite@8/.test(commands(byId['react-vite'])), 'react-vite uses Vite 8');
    assert.ok(/--webpack/.test(commands(byId.nextjs)) && /--port 3000/.test(commands(byId.nextjs)), 'nextjs runs with webpack on 3000');
    assert.ok(/http\.server/.test(commands(byId['web-basic'])));
    assert.ok(/-m venv/.test(commands(byId['python-script'])));
    assert.ok(/numpy/.test(commands(byId['python-data'])) && /pandas/.test(commands(byId['python-data'])));
    for (const r of recipes) assert.strictEqual(r.steps[r.steps.length - 1].type, 'test', r.id + ' ends with a test');

    // The pack ids are the app's: ToolchainRegistry.kt lists the same set.
    const registry = fs.readFileSync(path.join(ROOT, 'android/app/src/main/kotlin/com/vscodroid/setup/ToolchainRegistry.kt'), 'utf8');
    const packs = [...registry.matchAll(/packName = "(toolchain_\w+)"/g)].map((m) => m[1]).sort();
    assert.deepStrictEqual(Object.keys(engine.PACKS).sort(), packs);
    for (const m of registry.matchAll(/packName = "(toolchain_\w+)",[\s\S]*?estimatedSize = ([\d_]+)/g)) {
        const bytes = Number(m[2].replace(/_/g, ''));
        assert.ok(engine.PACKS[m[1]].sizeMB * 1e6 >= bytes, m[1] + ' size is not understated');
    }

    // Validation catches what a broken recipe gets wrong.
    const good = byId['web-basic'];
    const broken = (/** @type {(r: any) => void} */ change) => {
        const r = JSON.parse(JSON.stringify(good));
        change(r);
        return engine.recipeProblems(r);
    };
    assert.deepStrictEqual(engine.recipeProblems(good), []);
    assert.ok(broken((r) => { r.steps[0].type = 'download'; }).some((p) => /unknown type/.test(p)));
    assert.ok(broken((r) => { r.steps[0].path = '../x'; }).some((p) => /inside the project/.test(p)));
    assert.ok(broken((r) => { r.steps[0].path = '/etc/x'; }).some((p) => /inside the project/.test(p)));
    assert.ok(broken((r) => { r.needs.toolchains = ['toolchain_go']; }).some((p) => /unknown pack/.test(p)));
    assert.ok(broken((r) => { r.needs.internet = false; }).some((p) => /internet/.test(p)));
    assert.ok(broken((r) => { r.needs.internet = 'pack'; }).some((p) => /toolchains is empty/.test(p)));
    assert.ok(broken((r) => { delete r.steps[0].title; }).some((p) => /title is missing/.test(p)));
    assert.ok(broken((r) => { delete r.sizeMB; }).some((p) => /sizeMB/.test(p)));
    assert.ok(broken((r) => { r.steps[4].profile = 'gone'; }).some((p) => /profile not written/.test(p)));
    assert.ok(broken((r) => { r.steps = r.steps.filter((/** @type {any} */ s) => s.type !== 'profile' && s.type !== 'test'); }).some((p) => /run profile/.test(p)));
    assert.ok(broken((r) => { r.steps.unshift({ type: 'toolchain', title: 't', pack: 'toolchain_java' }); }).some((p) => /not in needs/.test(p)));
    assert.ok(engine.recipeProblems({ ...good, id: 'x' }, { fileName: 'web-basic.json' }).some((p) => /file name/.test(p)));
    assert.deepStrictEqual(engine.recipeProblems(null), ['not a JSON object']);

    const summaries = engine.summaries(recipes);
    const java = summaries.find((s) => s.id === 'java');
    assert.deepStrictEqual(java.toolchains, [{ pack: 'toolchain_java', label: 'Java 21' }]);
    assert.strictEqual(typeof java.sizeMB, 'number');
    return byId;
}

// ---- names, needs, planning ---------------------------------------------------

function checkNames() {
    assert.strictEqual(engine.nameProblem('my-app'), null);
    assert.strictEqual(engine.nameProblem('My App 2'), null);
    assert.strictEqual(engine.nameProblem('café'), null);
    assert.ok(engine.nameProblem(''));
    assert.ok(engine.nameProblem('a/b'));
    assert.ok(engine.nameProblem('.'));
    assert.ok(engine.nameProblem('..'));
    assert.strictEqual(engine.nameProblem('...'), null);
    assert.strictEqual(engine.nameProblem('a'.repeat(255)), null);
    assert.ok(engine.nameProblem('a'.repeat(256)));
    assert.ok(engine.nameProblem('é'.repeat(128)), '256 bytes in 128 characters');
    assert.ok(engine.nameProblem('line\nbreak'));
    assert.ok(engine.nameProblem(undefined));

    assert.strictEqual(engine.packageName('My App!'), 'my-app');
    assert.strictEqual(engine.packageName('..'), 'app');
    assert.strictEqual(engine.safeLabel('a"b<c>'), 'abc');
    assert.strictEqual(engine.shellQuote("it's"), "'it'\\''s'");
    assert.strictEqual(engine.render('{{name}}/{{pkg}}/{{other}}', { name: 'N', pkg: 'p' }), 'N/p/{{other}}');

    const projects = path.join(tmp, 'names');
    fs.mkdirSync(path.join(projects, 'taken'), { recursive: true });
    fs.writeFileSync(path.join(projects, 'taken', 'x'), '');
    fs.mkdirSync(path.join(projects, 'empty'));
    fs.writeFileSync(path.join(projects, 'file'), '');
    assert.match(engine.projectFolder(projects, 'taken').error, /not empty/);
    assert.match(engine.projectFolder(projects, 'file').error, /not a folder/);
    assert.strictEqual(engine.projectFolder(projects, 'empty').dir, path.join(projects, 'empty'));
    assert.strictEqual(engine.projectFolder(projects, 'new').dir, path.join(projects, 'new'));
    assert.match(engine.projectFolder(projects, '../x').error, /"\/"/);
    assert.strictEqual(engine.defaultName(projects, 'taken'), 'taken-2');
    assert.strictEqual(engine.defaultName(projects, 'fresh'), 'fresh');

    assert.strictEqual(engine.packId('java'), 'toolchain_java');
    assert.strictEqual(engine.packId('toolchain_cpp'), 'toolchain_cpp');
    assert.strictEqual(engine.packId('C++'), 'toolchain_cpp');
    assert.strictEqual(engine.packId('go'), null);
}

function checkNeeds(byId) {
    const none = new Set();
    assert.deepStrictEqual(engine.missingNeeds(byId['web-basic'], { freeMB: 100, internet: false, installed: none }), []);
    const space = engine.missingNeeds(byId['web-basic'], { freeMB: 2, internet: null, installed: none });
    assert.strictEqual(space.length, 1);
    assert.match(space[0], /needs 5 MB and the phone has 2 MB free/);

    // A pack to install adds twice its size plus the buffer, and needs the internet.
    const java = byId.java;
    const needMB = java.needs.minFreeMB + 2 * engine.PACKS.toolchain_java.sizeMB + 50;
    const both = engine.missingNeeds(java, { freeMB: 100, internet: false, installed: none });
    assert.strictEqual(both.length, 2);
    assert.ok(both[0].includes('needs ' + needMB + ' MB'));
    assert.match(both[1], /Java 21 pack has to be downloaded/);
    assert.deepStrictEqual(engine.missingNeeds(java, { freeMB: 10, internet: false, installed: new Set(['toolchain_java']) }), []);
    assert.ok(engine.needsInternetCheck(java, none));
    assert.ok(!engine.needsInternetCheck(java, new Set(['toolchain_java'])));
    assert.ok(!engine.needsInternetCheck(byId['python-script'], none));
    assert.match(engine.missingNeeds(byId.nextjs, { freeMB: 5000, internet: false, installed: none })[0], /No internet connection/);
    assert.deepStrictEqual(engine.missingNeeds(byId.nextjs, { freeMB: null, internet: true, installed: none }), []);

    const home = path.join(tmp, 'packs-home');
    fs.mkdirSync(path.join(home, '.vscodroid'), { recursive: true });
    assert.deepStrictEqual([...engine.installedPacks(home)], []);
    fs.writeFileSync(path.join(home, '.vscodroid', 'toolchains.json'), JSON.stringify([{ name: 'java' }, { name: 'ruby' }, {}]));
    assert.deepStrictEqual([...engine.installedPacks(home)].sort(), ['toolchain_java', 'toolchain_ruby']);
    fs.writeFileSync(path.join(home, '.vscodroid', 'toolchains.json'), '{broken');
    assert.deepStrictEqual([...engine.installedPacks(home)], []);

    const plan = engine.planSteps(java, new Set(['toolchain_java']), { name: 'n' });
    assert.strictEqual(plan.length, java.steps.length);
    assert.strictEqual(plan[0].alreadyDone, true);
    assert.strictEqual(engine.planSteps(java, none, {})[0].alreadyDone, false);

    const run = JSON.parse(engine.runJson([{ name: 'dev', command: 'npm run dev', port: 5173 }, { name: 'x', command: 'y' }]));
    assert.deepStrictEqual(run, { profiles: [
        { name: 'dev', command: 'npm run dev', port: 5173, where: 'phone', env: {} },
        { name: 'x', command: 'y', where: 'phone', env: {} },
    ] });
}

async function checkThrottle() {
    let now = 0;
    const sent = [];
    const t = new engine.LineThrottle((l) => sent.push(l), 250, () => now);
    t.push('a');
    t.push('b');
    t.push('c');
    assert.deepStrictEqual(sent, ['a'], 'lines inside the interval are held');
    now = 300;
    await new Promise((r) => setTimeout(r, 300));
    assert.deepStrictEqual(sent, ['a', 'c'], 'the newest held line goes out next');
    t.push('d');
    t.clear();
    await new Promise((r) => setTimeout(r, 300));
    assert.deepStrictEqual(sent, ['a', 'c'], 'clear drops what is held');
}

// ---- CLI output ---------------------------------------------------------------

function checkCliOutput(byId) {
    const list = kit.formatList(engine.summaries([byId['web-basic'], byId.java]));
    assert.match(list, /web-basic {2}Web page: /);
    assert.match(list, /works offline/);
    assert.match(list, /internet for the pack download/);
    assert.match(list, /installs Java 21/);

    const ev = (state, extra = {}) => ({ runId: 'r', recipe: 'x', project: '/p', step: { index: 0, total: 2, title: 'Write it', state }, line: 'last', done: null, ...extra });
    assert.deepStrictEqual(kit.formatEvent(ev('running')), []);
    assert.deepStrictEqual(kit.formatEvent(ev('ok')), ['[ok]   Write it']);
    assert.deepStrictEqual(kit.formatEvent(ev('fail', { tail: ['e1', 'e2'] })), ['[fail] Write it', '       e1', '       e2']);
    assert.deepStrictEqual(kit.formatEvent(ev('fail')), ['[fail] Write it', '       last']);
    assert.deepStrictEqual(kit.formatEvent(ev('fail', { done: { ok: false } })), []);

    const home = process.env.HOME || os.homedir();
    const ready = kit.formatResult({ ok: true, projectPath: path.join(home, 'projects', 'my app'), message: 'm', run: 'npm run dev' });
    assert.deepStrictEqual(ready, ['Ready: ~/projects/my app', "Run it: cd ~'/projects/my app' && npm run dev", 'Or open the folder in Diligent and tap Run.']);
    assert.deepStrictEqual(kit.formatResult({ ok: false, projectPath: '/p', message: 'No room.', run: null }), ['No room.']);

    const doctor = kit.formatDoctor({
        tools: [{ name: 'node', version: 'v24.0.0' }, { name: 'git', version: null }],
        packs: ['toolchain_java'],
        storage: { freeMB: 1234.5, totalMB: 9999 },
        health: { checks: [{ id: 'a', title: 'Server', state: 'pass', detail: 'up' }, { id: 'b', title: 'Disk', state: 'fail' }, { id: 'c', title: 'X', state: 'unknown' }] },
    });
    assert.match(doctor, /\[ok\] {3}node v24\.0\.0/);
    assert.match(doctor, /\[fail\] git was not found/);
    assert.match(doctor, /Java 21/);
    assert.match(doctor, /1234 MB free of 9999 MB/);
    assert.match(doctor, /\[ok\] {3}Server: up/);
    assert.match(doctor, /\[fail\] Disk/);
    assert.match(kit.formatDoctor({ tools: [], packs: [], storage: null, health: null }), /health checks unavailable/);
    assert.match(kit.HELP, /kit add <recipe> \[name\]/);
}

// ---- socket protocol ------------------------------------------------------------

async function checkProtocol() {
    const d = new server.LineDecoder();
    assert.deepStrictEqual(d.feed('{"a":1}\n{"b"'), [{ value: { a: 1 } }]);
    assert.deepStrictEqual(d.feed(':2}\nnope\n\n'), [{ value: { b: 2 } }, { error: 'not JSON' }]);
    assert.deepStrictEqual(new server.LineDecoder().feed('x'.repeat(server.MAX_LINE + 1)), [{ error: 'line too long' }]);
    assert.strictEqual(server.encode({ a: 1 }), '{"a":1}\n');

    const sent = [];
    const handlers = {
        list: () => ['r'],
        add: async (args, onEvent) => { onEvent({ n: 1 }); return { got: args.recipe }; },
        cancel: () => true,
        remove: async () => { throw new Error('nope'); },
        health: async () => null,
    };
    await server.handle({ id: 7, op: 'list' }, handlers, (m) => sent.push(m));
    await server.handle({ id: 8, op: 'add', args: { recipe: 'x' } }, handlers, (m) => sent.push(m));
    await server.handle({ id: 9, op: 'remove' }, handlers, (m) => sent.push(m));
    await server.handle({ id: 10, op: 'rm -rf' }, handlers, (m) => sent.push(m));
    await server.handle('junk', handlers, (m) => sent.push(m));
    assert.deepStrictEqual(sent, [
        { id: 7, ok: true, result: ['r'] },
        { id: 8, event: { n: 1 } },
        { id: 8, ok: true, result: { got: 'x' } },
        { id: 9, ok: false, error: 'nope' },
        { id: 10, ok: false, error: 'Unknown request: rm -rf' },
        { id: null, ok: false, error: 'Unknown request: null' },
    ]);

    // A real socket: stale file removed, owner-only mode, a second engine stands back.
    // Short path: a Unix socket path is limited to about 100 bytes.
    const sockDir = fs.mkdtempSync(path.join('/tmp', 'dk-'));
    const sock = path.join(sockDir, 'kit.sock');
    fs.writeFileSync(sock, 'stale');
    const srv = server.createServer(handlers);
    assert.strictEqual(await server.listen(srv, sock), true);
    assert.strictEqual(fs.statSync(sock).mode & 0o777, 0o600);
    assert.strictEqual(await server.listen(server.createServer(handlers), sock), false, 'a live socket is left alone');
    const reply = await kit.request('list', {}, undefined, sock);
    assert.deepStrictEqual(reply, { id: 1, ok: true, result: ['r'] });
    const events = [];
    const added = await kit.request('add', { recipe: 'y' }, (e) => events.push(e), sock);
    assert.deepStrictEqual(events, [{ n: 1 }]);
    assert.deepStrictEqual(added.result, { got: 'y' });
    // Bad input does not take the server down.
    await new Promise((resolve) => {
        const c = net.connect(sock, () => c.write('not json\n'));
        c.on('data', (b) => { assert.match(String(b), /Bad request/); c.destroy(); resolve(); });
    });
    assert.deepStrictEqual((await kit.request('list', {}, undefined, sock)).result, ['r']);
    await new Promise((r) => srv.close(r));
    fs.rmSync(sockDir, { recursive: true, force: true });
}

// ---- runs, with stand-ins for the app ----------------------------------------------

function fakeApp(home) {
    const calls = [];
    return {
        calls,
        executeCommand: async (cmd, ...args) => {
            calls.push([cmd, ...args]);
            if (cmd === 'diligent.bridge.installToolchain') {
                // The app's install writes its record a little later.
                setTimeout(() => {
                    const file = path.join(home, '.vscodroid', 'toolchains.json');
                    fs.mkdirSync(path.dirname(file), { recursive: true });
                    fs.writeFileSync(file, JSON.stringify([{ name: args[0].replace('toolchain_', '') }]));
                }, 150);
                return undefined;
            }
            if (cmd === 'diligent.bridge.getHealth') return JSON.stringify({ checks: [{ id: 'x', title: 'X', state: 'pass', detail: '' }] });
            return undefined;
        },
    };
}

async function checkServiceAndFailures() {
    const home = path.join(tmp, 'svc-home');
    const projectsDir = path.join(home, 'projects');
    fs.mkdirSync(projectsDir, { recursive: true });
    const app = fakeApp(home);
    const service = createService({
        extensionDir: DIR, home, projectsDir, executeCommand: app.executeCommand,
        runOptions: { checkInternet: async () => false, free: () => 100000, timeouts: { pollMs: 20, toolchainMs: 5000 } },
    });
    assert.deepStrictEqual(service.problems, []);
    assert.strictEqual(service.list().length, 9);
    assert.deepStrictEqual(await service.health(), { checks: [{ id: 'x', title: 'X', state: 'pass', detail: '' }] });
    const silent = createService({ extensionDir: DIR, home, projectsDir, executeCommand: async () => { throw new Error('no bridge'); } });
    assert.strictEqual(await silent.health(), null);

    // Fail early: nextjs offline stops before anything is written.
    const events = [];
    const r = await service.add({ recipe: 'nextjs', name: 'web' }, (e) => events.push(e));
    assert.strictEqual(r.ok, false);
    assert.match(r.message, /No internet connection/);
    assert.ok(!fs.existsSync(path.join(projectsDir, 'web')), 'nothing written before the checks pass');
    assert.strictEqual(events.length, 1);
    assert.strictEqual(events[0].done.ok, false);

    // The progress the app receives has the contract's shape.
    const toApp = app.calls.filter((c) => c[0] === 'diligent.bridge.kitProgress').map((c) => JSON.parse(c[1]));
    assert.ok(toApp.length >= 1);
    const p = toApp[toApp.length - 1];
    assert.deepStrictEqual(Object.keys(p).sort(), ['done', 'line', 'project', 'recipe', 'runId', 'step']);
    assert.deepStrictEqual(Object.keys(p.step).sort(), ['index', 'state', 'title', 'total']);
    assert.strictEqual(p.project, path.join(projectsDir, 'web'));
    assert.deepStrictEqual(Object.keys(p.done).sort(), ['message', 'ok', 'projectPath']);

    // A bad name and an unknown recipe.
    assert.match((await service.add({ recipe: 'web-basic', name: '..' }, () => {})).message, /"\."/);
    await assert.rejects(service.add({ recipe: 'cobol' }, () => {}), /no recipe "cobol"/);
    const refusedId = await service.start('cobol');
    const refused = app.calls.map((c) => c[0] === 'diligent.bridge.kitProgress' && JSON.parse(c[1])).filter((x) => x && x.runId === refusedId);
    assert.strictEqual(refused.length, 1);
    assert.match(refused[0].done.message, /no recipe/);
    assert.strictEqual(refused[0].done.projectPath, path.join(projectsDir, 'cobol'), 'projectPath is always set');

    // A toolchain step asks the app, then sees the pack in the app's record.
    // The java recipe stops at its test (no java here), after the pack step.
    const online = createService({
        extensionDir: DIR, home, projectsDir, executeCommand: app.executeCommand,
        runOptions: { checkInternet: async () => true, free: () => 100000, timeouts: { pollMs: 20, toolchainMs: 5000 } },
    });
    const javaEvents = [];
    const javaRun = await online.add({ recipe: 'java', name: 'hello-java' }, (e) => javaEvents.push(e));
    assert.ok(app.calls.some((c) => c[0] === 'diligent.bridge.installToolchain' && c[1] === 'toolchain_java'));
    assert.ok(javaEvents.some((e) => e.step.index === 0 && e.step.state === 'ok'), 'pack step passed');
    assert.ok(fs.existsSync(path.join(projectsDir, 'hello-java', 'Main.java')));
    if (!javaRun.ok) assert.match(javaRun.message, /Step 4 of 4 failed/);

    // A pack that never appears times out with a sentence.
    const never = createService({
        extensionDir: DIR, home: path.join(tmp, 'never-home'), projectsDir, executeCommand: async () => undefined,
        runOptions: { checkInternet: async () => true, free: () => 100000, timeouts: { pollMs: 10, toolchainMs: 100 } },
    });
    const timedOut = await never.add({ recipe: 'ruby', name: 'r1' }, () => {});
    assert.match(timedOut.message, /did not finish installing/);

    // Cancel stops a running command.
    const slowDir = path.join(tmp, 'slow-ext');
    fs.mkdirSync(path.join(slowDir, 'recipes'), { recursive: true });
    fs.mkdirSync(path.join(slowDir, 'templates'), { recursive: true });
    fs.writeFileSync(path.join(slowDir, 'recipes', 'slow.json'), JSON.stringify({
        id: 'slow', title: 'Slow', summary: 's', sizeMB: 0, tested: {},
        needs: { toolchains: [], minFreeMB: 0, internet: 'no' },
        steps: [
            { type: 'run', title: 'Wait', command: 'echo start; sleep 30' },
            { type: 'profile', title: 'p', profiles: [{ name: 'x', command: 'true' }] },
            { type: 'test', title: 't', profile: 'x', expect: { output: 'x' } },
        ],
    }));
    const slow = createService({ extensionDir: slowDir, home, projectsDir, executeCommand: async () => undefined });
    const started = Date.now();
    const pending = slow.add({ recipe: 'slow', name: 'slow' }, (e) => {
        if (e.line === 'start') slow.cancel({ runId: e.runId });
    });
    const cancelled = await pending;
    assert.strictEqual(cancelled.ok, false);
    assert.match(cancelled.message, /cancelled/);
    assert.ok(Date.now() - started < 10_000, 'cancel ended the command');
    assert.strictEqual(slow.activeRunId, null);
}

// ---- end to end ------------------------------------------------------------------

function hasPython() {
    try {
        execFileSync('python3', ['--version'], { stdio: 'ignore' });
        return true;
    } catch {
        return false;
    }
}

async function checkEndToEnd() {
    if (!hasPython()) {
        console.log('python3 not found: web-basic and python-script end to end skipped');
        return;
    }
    const home = path.join(tmp, 'e2e-home');
    const projectsDir = path.join(home, 'projects');
    fs.mkdirSync(projectsDir, { recursive: true });
    const app = fakeApp(home);
    const service = createService({
        extensionDir: DIR, home, projectsDir, executeCommand: app.executeCommand,
        // Offline: neither recipe may ask.
        runOptions: { checkInternet: async () => { throw new Error('asked for the internet'); } },
    });

    const sockDir = fs.mkdtempSync(path.join('/tmp', 'dk-'));
    const sock = path.join(sockDir, 'kit.sock');
    const srv = server.createServer(service);
    await server.listen(srv, sock);
    try {
        for (const [recipe, name, files] of [
            ['web-basic', 'my site', ['index.html', 'style.css', 'script.js']],
            ['python-script', 'hello', ['main.py', '.gitignore', '.venv/pyvenv.cfg']],
        ]) {
            const lines = [];
            const reply = await kit.request('add', { recipe, name }, (e) => lines.push(...kit.formatEvent(e)), sock);
            assert.strictEqual(reply.ok, true);
            assert.strictEqual(reply.result.ok, true, recipe + ': ' + reply.result.message + '\n' + lines.join('\n'));
            const dir = path.join(projectsDir, name);
            assert.strictEqual(reply.result.projectPath, dir);
            const total = service.recipes.get(recipe).steps.length;
            assert.strictEqual(lines.length, total, 'one line per step');
            assert.ok(lines.every((l) => l.startsWith('[ok]')), lines.join('\n'));
            for (const f of files) assert.ok(fs.existsSync(path.join(dir, f)), recipe + ': ' + f);
            const runJson = JSON.parse(fs.readFileSync(path.join(dir, '.diligent', 'run.json'), 'utf8'));
            assert.strictEqual(runJson.profiles[0].where, 'phone');
            assert.ok(kit.formatResult(reply.result)[0].startsWith('Ready: '));

            // The same name again is refused, since the folder is not empty.
            const again = await kit.request('add', { recipe, name }, () => {}, sock);
            assert.strictEqual(again.result.ok, false);
            assert.match(again.result.message, /not empty/);
        }
        assert.match(fs.readFileSync(path.join(projectsDir, 'my site', 'index.html'), 'utf8'), /<title>my site<\/title>/);
        assert.match(fs.readFileSync(path.join(projectsDir, 'hello', 'main.py'), 'utf8'), /Hello from hello/);
        assert.ok(!app.calls.some((c) => c[0] === 'diligent.bridge.installToolchain'));
        const progress = app.calls.filter((c) => c[0] === 'diligent.bridge.kitProgress').map((c) => JSON.parse(c[1]));
        assert.ok(progress.some((p) => p.done && p.done.ok && p.recipe === 'python-script'));
    } finally {
        await new Promise((r) => srv.close(r));
        fs.rmSync(sockDir, { recursive: true, force: true });
    }
}

// ---- the kit command itself -----------------------------------------------------------

function checkCliProcess() {
    const cli = path.join(DIR, 'kit.js');
    const env = { ...process.env, HOME: path.join(tmp, 'cli-home') };
    fs.mkdirSync(env.HOME, { recursive: true });
    const help = execFileSync(process.execPath, [cli, 'help'], { env, encoding: 'utf8' });
    assert.match(help, /kit doctor/);
    let failed = null;
    try {
        execFileSync(process.execPath, [cli, 'list'], { env, encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] });
    } catch (err) {
        failed = err;
    }
    assert.ok(failed && failed.status === 1 && /engine is not running/.test(failed.stderr));
    const doctor = execFileSync(process.execPath, [cli, 'doctor'], { env, encoding: 'utf8' });
    assert.match(doctor, /health checks unavailable/);
    assert.match(doctor, /Storage/);
}

// ---- .bashrc -------------------------------------------------------------------------

function checkBashrc() {
    const setup = fs.readFileSync(path.join(ROOT, 'android/app/src/main/kotlin/com/vscodroid/setup/FirstRunSetup.kt'), 'utf8');
    // KIT_BLOCK in Kotlin source: `kit() { node "$HOME/.diligent/kit/kit.js" "$@"; }` with escapes.
    assert.ok(setup.includes('"kit() { node \\"\\$HOME/.diligent/kit/kit.js\\" \\"\\$@\\"; }\\n"'), 'FirstRunSetup writes the kit function');
}

(async () => {
    const byId = checkRecipes();
    checkNames();
    checkNeeds(byId);
    await checkThrottle();
    checkCliOutput(byId);
    await checkProtocol();
    await checkServiceAndFailures();
    await checkEndToEnd();
    checkCliProcess();
    checkBashrc();
    console.log('ok: diligent-kit recipes, needs, planning, names, CLI, socket and end-to-end runs');
})().catch((err) => {
    console.error(err);
    process.exit(1);
});
