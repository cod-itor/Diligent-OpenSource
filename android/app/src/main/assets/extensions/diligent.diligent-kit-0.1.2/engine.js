// @ts-check
'use strict';

/**
 * The kit engine: recipes, the checks made before the first step, and the
 * runner that turns a recipe into a project folder.
 *
 * Nothing here needs the editor. Every effect that does (installing a
 * toolchain pack through the app, reading the app's health checks) arrives as
 * a function in `deps`, so scripts/test-diligent-kit.js runs the same code
 * with stand-ins, and runs web-basic and python-script for real in a
 * temporary HOME.
 *
 * Commands run through `bash -c` with an argv array. The only text put into
 * shell code is the recipe's own command, after `{{name}}` style placeholders
 * are replaced with values that are quoted for the shell first.
 */

const fs = require('fs');
const net = require('net');
const dns = require('dns');
const os = require('os');
const path = require('path');
const { spawn } = require('child_process');

const STEP_TYPES = ['toolchain', 'run', 'write', 'profile', 'test'];

/**
 * `needs.internet`: "no" (works offline), "once" (downloads packages while it
 * is set up) or "pack" (only a toolchain pack download, which is skipped when
 * the pack is already installed).
 */
const INTERNET = ['no', 'once', 'pack'];

/**
 * The toolchain packs a recipe may name, as ToolchainRegistry.kt lists them.
 * `sizeMB` is the unpacked size there (estimatedSize), rounded up. A first
 * install over HTTP holds two copies at once, so the space check asks for
 * twice that plus 50 MB, as the app's own pre-flight does.
 */
const PACKS = Object.freeze({
    toolchain_ruby: { label: 'Ruby', sizeMB: 41 },
    toolchain_java: { label: 'Java 21', sizeMB: 186 },
    toolchain_cpp: { label: 'C/C++', sizeMB: 260 },
    // Packs that are being added to ToolchainRegistry.kt. Until a Diligent
    // release publishes them, an install fails and the recipe says so. The
    // sizes are generous guesses, which only make the space check ask for
    // more; the self-check holds each to at least the registry's figure.
    toolchain_php: { label: 'PHP', sizeMB: 80 },
    toolchain_lua: { label: 'Lua', sizeMB: 5 },
    toolchain_perl: { label: 'Perl', sizeMB: 70 },
    toolchain_kotlin: { label: 'Kotlin', sizeMB: 120 },
    toolchain_rust: { label: 'Rust', sizeMB: 600 },
});

const PACK_BUFFER_MB = 50;
/** How long a pack install may take before the step fails. */
const TOOLCHAIN_TIMEOUT_MS = 20 * 60_000;
const TOOLCHAIN_POLL_MS = 2_000;
const RUN_TIMEOUT_MS = 15 * 60_000;
const TEST_TIMEOUT_MS = 120_000;
/** About four output lines a second reach the app, and no more. */
const LINE_INTERVAL_MS = 250;
const INTERNET_HOST = 'registry.npmjs.org';
const INTERNET_TIMEOUT_MS = 4_000;
const TAIL_LINES = 15;
/** The record of prepared recipes, under the terminal's HOME. */
const OFFLINE_FILE = path.join('.diligent', 'kit-offline.json');
/** Where `kit prepare` runs a recipe, under HOME; removed when it ends. */
const PREPARE_DIR = path.join('.diligent', 'kit-prepare');
/** Python wheels `kit prepare` downloads, one folder per recipe, under HOME. */
const WHEELS_DIR = path.join('.diligent', 'kit-wheels');
/** A `pip install` in a recipe command, which prepare turns into a `pip download` first. */
const PIP_INSTALL = /\bpip3? install\b/;

// ---- names -------------------------------------------------------------

/**
 * Why `name` cannot be a project folder name, or null when it can.
 * @param {unknown} name
 * @returns {string | null}
 */
function nameProblem(name) {
    if (typeof name !== 'string' || name.length === 0) return 'The project name is empty.';
    if (name.includes('/')) return 'The project name cannot contain "/".';
    if (name === '.' || name === '..') return 'The project name cannot be "." or "..".';
    if (Buffer.byteLength(name, 'utf8') > 255) return 'The project name is longer than 255 bytes.';
    // A line break or another control character would end up in run.json,
    // the shell and the terminal's title.
    if (/[\u0000-\u001f\u007f]/.test(name)) return 'The project name cannot contain control characters.';
    return null;
}

/**
 * A package name npm accepts, made from the project name: lower case, and
 * only letters, digits, `-`, `.` and `_`.
 * @param {string} name
 */
function packageName(name) {
    const cleaned = name.toLowerCase().replace(/[^a-z0-9._-]+/g, '-').replace(/^[._-]+|-+$/g, '');
    return cleaned.slice(0, 214) || 'app';
}

/**
 * The name as it may appear inside source code (a string literal, a
 * comment): only letters, digits, space, `.`, `_` and `-`.
 * @param {string} name
 */
function safeLabel(name) {
    return name.replace(/[^\p{L}\p{N} ._-]+/gu, '').trim() || 'app';
}

/** @param {string} value */
function shellQuote(value) {
    return "'" + value.replace(/'/g, "'\\''") + "'";
}

/**
 * Replaces `{{name}}`, `{{pkg}}` and `{{project}}` in a template or command.
 * @param {string} text
 * @param {Record<string, string>} vars
 */
function render(text, vars) {
    return text.replace(/\{\{(\w+)\}\}/g, (whole, key) => (key in vars ? vars[key] : whole));
}

// ---- recipes ------------------------------------------------------------

/**
 * Every problem with one recipe, as sentences; an empty array when it is valid.
 * @param {any} r
 * @param {{ templateExists?: (p: string) => boolean, fileName?: string }} [options]
 * @returns {string[]}
 */
function recipeProblems(r, options = {}) {
    const p = [];
    if (!r || typeof r !== 'object' || Array.isArray(r)) return ['not a JSON object'];
    const str = (/** @type {unknown} */ v) => typeof v === 'string' && v.trim().length > 0;
    if (!str(r.id) || !/^[a-z0-9][a-z0-9-]*$/.test(r.id)) p.push('id must be lower case letters, digits and -');
    if (options.fileName && r.id + '.json' !== options.fileName) p.push('id does not match the file name');
    for (const key of ['title', 'summary']) if (!str(r[key])) p.push(key + ' is missing');
    if (r.language !== undefined && !str(r.language)) p.push('language must be a name, such as Python');
    if (!Number.isInteger(r.sizeMB) || r.sizeMB < 0) p.push('sizeMB must be a whole number');
    if (!r.tested || typeof r.tested !== 'object' || Array.isArray(r.tested)) p.push('tested must be an object');
    const needs = r.needs;
    if (!needs || typeof needs !== 'object') {
        p.push('needs is missing');
    } else {
        if (!Array.isArray(needs.toolchains)) p.push('needs.toolchains must be an array');
        else for (const t of needs.toolchains) if (!(t in PACKS)) p.push('needs.toolchains names an unknown pack: ' + t);
        if (!Number.isInteger(needs.minFreeMB) || needs.minFreeMB < 0) p.push('needs.minFreeMB must be a whole number');
        if (!INTERNET.includes(needs.internet)) p.push('needs.internet must be "no", "once" or "pack"');
        else if (needs.internet === 'pack' && !(Array.isArray(needs.toolchains) && needs.toolchains.length)) {
            p.push('needs.internet is "pack" but needs.toolchains is empty');
        }
    }
    if (!Array.isArray(r.steps) || r.steps.length === 0) {
        p.push('steps must be a non-empty array');
        return p;
    }
    /** @type {Set<string>} */
    const profiles = new Set();
    r.steps.forEach((/** @type {any} */ s, /** @type {number} */ i) => {
        const at = 'step ' + (i + 1) + ': ';
        if (!s || typeof s !== 'object') { p.push(at + 'not an object'); return; }
        if (!STEP_TYPES.includes(s.type)) { p.push(at + 'unknown type ' + s.type); return; }
        if (!str(s.title)) p.push(at + 'title is missing');
        if (s.type === 'toolchain') {
            if (!(s.pack in PACKS)) p.push(at + 'unknown pack ' + s.pack);
            else if (needs && Array.isArray(needs.toolchains) && !needs.toolchains.includes(s.pack)) {
                p.push(at + 'pack ' + s.pack + ' is not in needs.toolchains');
            }
        } else if (s.type === 'run') {
            if (!str(s.command)) p.push(at + 'command is missing');
        } else if (s.type === 'write') {
            if (!str(s.template)) p.push(at + 'template is missing');
            else if (options.templateExists && !options.templateExists(s.template)) p.push(at + 'template not found: ' + s.template);
            if (!str(s.path) || !safeRelative(s.path)) p.push(at + 'path must stay inside the project');
        } else if (s.type === 'profile') {
            if (!Array.isArray(s.profiles) || s.profiles.length === 0) p.push(at + 'profiles must be a non-empty array');
            else for (const prof of s.profiles) {
                if (!prof || !str(prof.name) || !str(prof.command)) p.push(at + 'each profile needs a name and a command');
                else profiles.add(prof.name);
                if (prof && prof.port !== undefined && !(Number.isInteger(prof.port) && prof.port >= 1 && prof.port <= 65535)) {
                    p.push(at + 'bad port');
                }
            }
        } else if (s.type === 'test') {
            if (!profiles.has(s.profile)) p.push(at + 'test names a profile not written before it: ' + s.profile);
            const e = s.expect;
            if (!e || (e.port === undefined && e.output === undefined)) p.push(at + 'expect needs a port or an output');
            if (e && e.output !== undefined) {
                try { new RegExp(e.output); } catch { p.push(at + 'expect.output is not a regular expression'); }
            }
        }
    });
    const firstProfile = r.steps.findIndex((/** @type {any} */ s) => s && s.type === 'profile');
    if (firstProfile < 0) p.push('a recipe must write a run profile');
    return p;
}

/** Whether a template path stays inside the project folder. @param {string} rel */
function safeRelative(rel) {
    if (path.isAbsolute(rel) || rel.includes('\\')) return false;
    return !rel.split('/').some((part) => part === '..' || part === '');
}

/**
 * Reads every `recipes/<id>.json` in `extensionDir`.
 * @param {string} extensionDir
 * @returns {{ recipes: any[], problems: string[] }}
 */
function loadRecipes(extensionDir) {
    const dir = path.join(extensionDir, 'recipes');
    const templates = path.join(extensionDir, 'templates');
    /** @type {any[]} */
    const recipes = [];
    /** @type {string[]} */
    const problems = [];
    for (const file of fs.readdirSync(dir).filter((f) => f.endsWith('.json')).sort()) {
        let recipe;
        try {
            recipe = JSON.parse(fs.readFileSync(path.join(dir, file), 'utf8'));
        } catch (err) {
            problems.push(file + ': ' + String(err));
            continue;
        }
        const found = recipeProblems(recipe, {
            fileName: file,
            templateExists: (t) => safeRelative(t) && fs.existsSync(path.join(templates, t)),
        });
        if (found.length) problems.push(...found.map((x) => file + ': ' + x));
        else recipes.push(recipe);
    }
    return { recipes, problems };
}

/**
 * What the Setup screen and `kit list` show for each recipe. With `home`,
 * `offline` says whether `kit add` works without the internet.
 * @param {any[]} recipes
 * @param {string} [home]
 */
function summaries(recipes, home) {
    const prepared = home ? preparedRecipes(home) : {};
    const installed = home ? installedPacks(home) : new Set();
    return recipes.map((r) => ({
        id: r.id,
        title: r.title,
        summary: r.summary,
        language: r.language || null,
        sizeMB: r.sizeMB,
        internet: r.needs.internet,
        toolchains: r.needs.toolchains.map((/** @type {string} */ t) => ({ pack: t, label: PACKS[/** @type {keyof PACKS} */ (t)].label })),
        offline: readyOffline(r, prepared, installed),
    }));
}

// ---- offline ------------------------------------------------------------

/**
 * The recipes `kit prepare` (or a finished `kit add`) left ready offline:
 * `~/.diligent/kit-offline.json`, `{"recipes": {"<id>": {"preparedAt", "tested"}}}`.
 * @param {string} home
 * @returns {Record<string, { preparedAt: string, tested: unknown }>}
 */
function preparedRecipes(home) {
    try {
        const state = JSON.parse(fs.readFileSync(path.join(home, OFFLINE_FILE), 'utf8'));
        const recipes = state && typeof state.recipes === 'object' && state.recipes ? state.recipes : {};
        return Array.isArray(recipes) ? {} : recipes;
    } catch {
        return {};
    }
}

/**
 * Records `recipe` as prepared, with the versions it was tested with: a
 * recipe whose `tested` changes (a newer Vite) needs preparing again.
 * @param {string} home @param {any} recipe @param {Date} [now]
 */
function markPrepared(home, recipe, now = new Date()) {
    const recipes = { ...preparedRecipes(home), [recipe.id]: { preparedAt: now.toISOString(), tested: recipe.tested } };
    const file = path.join(home, OFFLINE_FILE);
    fs.mkdirSync(path.dirname(file), { recursive: true });
    const tmp = file + '.tmp-' + process.pid;
    fs.writeFileSync(tmp, JSON.stringify({ recipes }, null, 2) + '\n');
    fs.renameSync(tmp, file);
}

/**
 * Whether `kit add` of `recipe` works without the internet: it needs none,
 * or its packs are installed and it downloads nothing else, or it was
 * prepared for the versions it is tested with (and its packs are still there).
 * @param {any} recipe
 * @param {Record<string, { tested: unknown }>} prepared
 * @param {Set<string>} installed
 */
function readyOffline(recipe, prepared, installed) {
    const packsThere = recipe.needs.toolchains.every((/** @type {string} */ t) => installed.has(t));
    if (recipe.needs.internet === 'no') return true;
    if (recipe.needs.internet === 'pack') return packsThere;
    const entry = prepared[recipe.id];
    return Boolean(entry) && packsThere && JSON.stringify(entry.tested) === JSON.stringify(recipe.tested);
}

/** Whether a recipe installs Python packages with pip. @param {any} recipe */
function usesPip(recipe) {
    return recipe.steps.some((/** @type {any} */ s) => s.type === 'run' && PIP_INSTALL.test(s.command));
}

/**
 * The command that fills the wheel folder for a `pip install` command:
 * the same packages, downloaded instead of installed.
 * @param {string} command @param {string} wheels
 */
function pipDownload(command, wheels) {
    return command.replace(PIP_INSTALL, (m) => m.replace('install', 'download --dest ' + shellQuote(wheels)));
}

/**
 * The environment a recipe's commands get for the cache. A prepared recipe
 * prefers npm's cache over the registry, and when there is no internet it
 * uses the cache alone, so a missing package fails at once rather than
 * after minutes of retries. pip looks in the recipe's wheel folder first,
 * and without the internet only there.
 * @param {{ prepared: boolean, internet: boolean | null, wheels: string }} c
 * @returns {Record<string, string>}
 */
function cacheEnv(c) {
    /** @type {Record<string, string>} */
    const env = {};
    if (!c.prepared) return env;
    env.npm_config_prefer_offline = 'true';
    if (c.internet === false) env.npm_config_offline = 'true';
    if (fs.existsSync(c.wheels)) {
        env.PIP_FIND_LINKS = c.wheels;
        if (c.internet === false) env.PIP_NO_INDEX = '1';
    }
    return env;
}

// ---- needs --------------------------------------------------------------

/**
 * Everything missing before step 1, as sentences a user can act on. Empty
 * means the recipe can start.
 *
 * A pack that is not installed yet is not missing: the recipe installs it.
 * It does add its space, and it needs the internet to download.
 *
 * A recipe prepared for offline use ([readyOffline]) does not need the
 * internet for its packages.
 *
 * @param {any} recipe
 * @param {{ freeMB: number | null, internet: boolean | null, installed: Set<string>, prepared?: boolean }} facts
 * @returns {string[]}
 */
function missingNeeds(recipe, facts) {
    const missing = [];
    const toInstall = recipe.needs.toolchains.filter((/** @type {string} */ t) => !facts.installed.has(t));
    const packMB = toInstall.reduce((/** @type {number} */ sum, /** @type {string} */ t) =>
        sum + 2 * PACKS[/** @type {keyof PACKS} */ (t)].sizeMB + PACK_BUFFER_MB, 0);
    const needMB = recipe.needs.minFreeMB + packMB;
    if (facts.freeMB !== null && facts.freeMB < needMB) {
        missing.push('Not enough free space: this needs ' + needMB + ' MB and the phone has ' +
            Math.floor(facts.freeMB) + ' MB free. Free up ' + Math.ceil(needMB - facts.freeMB) + ' MB and try again.');
    }
    const labels = toInstall.map((/** @type {string} */ t) => PACKS[/** @type {keyof PACKS} */ (t)].label);
    if (facts.internet === false) {
        if (recipe.needs.internet === 'once' && !facts.prepared) {
            missing.push('No internet connection: this recipe downloads packages. Connect to the internet, ' +
                'or use a recipe marked ready offline, and try again.');
        } else if (labels.length) {
            missing.push('No internet connection: the ' + labels.join(' and ') +
                ' pack has to be downloaded first. Connect to the internet and try again.');
        }
    }
    return missing;
}

/** Whether the internet needs to be checked at all for this recipe. @param {any} recipe @param {Set<string>} installed */
function needsInternetCheck(recipe, installed) {
    return recipe.needs.internet === 'once' || recipe.needs.toolchains.some((/** @type {string} */ t) => !installed.has(t));
}

/** Free megabytes under `dir`, or null when it cannot be read. @param {string} dir */
function freeMB(dir) {
    try {
        const s = fs.statfsSync(dir);
        return (s.bavail * s.bsize) / (1024 * 1024);
    } catch {
        return null;
    }
}

/**
 * Whether the internet answers: a DNS lookup, then a TCP connection to port
 * 443 of the npm registry, in a few seconds at most.
 * @returns {Promise<boolean>}
 */
function internetAnswers(host = INTERNET_HOST, timeoutMs = INTERNET_TIMEOUT_MS) {
    return new Promise((resolve) => {
        let settled = false;
        const done = (/** @type {boolean} */ ok) => { if (!settled) { settled = true; clearTimeout(timer); resolve(ok); } };
        const timer = setTimeout(() => done(false), timeoutMs);
        dns.lookup(host, (err, address) => {
            if (err || !address) return done(false);
            const socket = net.connect({ host: address, port: 443 });
            socket.once('connect', () => { socket.destroy(); done(true); });
            socket.once('error', () => { socket.destroy(); done(false); });
        });
    });
}

/**
 * The packs the app has installed, from its record `~/.vscodroid/toolchains.json`
 * (ToolchainManager's stateFile): an array of objects whose `name` is the
 * short name, `java` for `toolchain_java`.
 * @param {string} home
 * @returns {Set<string>}
 */
function installedPacks(home) {
    try {
        const state = JSON.parse(fs.readFileSync(path.join(home, '.vscodroid', 'toolchains.json'), 'utf8'));
        return new Set((Array.isArray(state) ? state : [])
            .map((e) => e && typeof e.name === 'string' ? 'toolchain_' + e.name : '')
            .filter(Boolean));
    } catch {
        return new Set();
    }
}

/** `java`, `toolchain_java` or `Java` to the pack id, or null. @param {string} name */
function packId(name) {
    const lower = String(name || '').toLowerCase();
    if (lower in PACKS) return lower;
    if ('toolchain_' + lower in PACKS) return 'toolchain_' + lower;
    if (lower === 'c' || lower === 'c++' || lower === 'clang') return 'toolchain_cpp';
    return null;
}

// ---- planning -----------------------------------------------------------

/**
 * The steps a run will show, with titles rendered. A toolchain step for a
 * pack already installed stays in the list (the count does not change under
 * the user) and is marked to finish at once.
 * @param {any} recipe
 * @param {Set<string>} installed
 * @param {Record<string, string>} vars
 */
function planSteps(recipe, installed, vars) {
    return recipe.steps.map((/** @type {any} */ s) => ({
        ...s,
        title: render(s.title, vars),
        alreadyDone: s.type === 'toolchain' && installed.has(s.pack),
    }));
}

/**
 * The folder for a new project, or why there cannot be one.
 * @param {string} projectsDir
 * @param {string} name
 * @returns {{ dir: string } | { error: string }}
 */
function projectFolder(projectsDir, name) {
    const problem = nameProblem(name);
    if (problem) return { error: problem };
    const dir = path.join(projectsDir, name);
    try {
        const st = fs.statSync(dir);
        if (!st.isDirectory()) return { error: dir + ' already exists and is not a folder. Choose another name.' };
        if (fs.readdirSync(dir).length > 0) return { error: dir + ' already exists and is not empty. Choose another name.' };
    } catch {
        // Missing: the ordinary case.
    }
    return { dir };
}

/**
 * A free default name: the recipe id, then id-2, id-3...
 * @param {string} projectsDir
 * @param {string} base
 */
function defaultName(projectsDir, base) {
    for (let n = 1; n < 1000; n += 1) {
        const name = n === 1 ? base : base + '-' + n;
        if (!('error' in projectFolder(projectsDir, name))) return name;
    }
    return base + '-' + Date.now();
}

/** The run.json Kotlin's RunConfig reads. @param {any[]} profiles */
function runJson(profiles) {
    return JSON.stringify({
        profiles: profiles.map((p) => {
            /** @type {Record<string, unknown>} */
            const out = { name: p.name, command: p.command };
            if (p.port !== undefined) out.port = p.port;
            out.where = 'phone';
            out.env = p.env || {};
            return out;
        }),
    }, null, 2) + '\n';
}

// ---- output -------------------------------------------------------------

/**
 * One terminal line for a finished step.
 * @param {'ok' | 'fail'} state
 * @param {string} title
 */
function stepLine(state, title) {
    return (state === 'ok' ? '[ok]   ' : '[fail] ') + title;
}

/**
 * Lets a line through at most every `intervalMs`; the newest held line goes
 * out with the next one allowed, or with `flush`.
 */
class LineThrottle {
    /** @param {(line: string) => void} send @param {number} [intervalMs] @param {() => number} [now] */
    constructor(send, intervalMs = LINE_INTERVAL_MS, now = Date.now) {
        this.send = send;
        this.intervalMs = intervalMs;
        this.now = now;
        this.last = -Infinity;
        /** @type {string | null} */
        this.held = null;
        /** @type {NodeJS.Timeout | null} */
        this.timer = null;
    }

    /** @param {string} line */
    push(line) {
        const t = this.now();
        if (t - this.last >= this.intervalMs) {
            this.last = t;
            this.held = null;
            this.send(line);
            return;
        }
        this.held = line;
        if (!this.timer) {
            this.timer = setTimeout(() => {
                this.timer = null;
                if (this.held !== null) this.push(this.held);
            }, Math.max(0, this.intervalMs - (t - this.last)));
        }
    }

    /** Drops what is held; the next state change carries the newest line anyway. */
    clear() {
        if (this.timer) clearTimeout(this.timer);
        this.timer = null;
        this.held = null;
    }
}

// ---- running ------------------------------------------------------------

/** The terminal's environment for a command, with the exec interceptor. @param {Record<string, string>} [extra] */
function commandEnv(extra = {}) {
    /** @type {Record<string, string | undefined>} */
    const env = { ...process.env, npm_config_yes: 'true', BROWSER: 'none', PYTHONUNBUFFERED: '1', ...extra };
    // Kotlin's RunScripts exports the same file for the same reason: a file
    // under the app's folder (`./hello`, a venv's python) only starts through it.
    const preload = path.join(process.env.PREFIX || '', 'lib', 'libtermux-exec.so');
    if (process.env.PREFIX && !env.LD_PRELOAD && fs.existsSync(preload)) env.LD_PRELOAD = preload;
    return env;
}

/** The bash to run commands with: the terminal's when it is bash. */
function bashPath() {
    return process.env.SHELL && path.basename(process.env.SHELL) === 'bash' ? process.env.SHELL : 'bash';
}

/**
 * Runs `command` with bash in `cwd`, in its own process group so cancel and
 * the test step can stop everything it started.
 * @param {string} command
 * @param {string} cwd
 * @param {(line: string) => void} onLine
 * @param {Record<string, string>} [env] added to the terminal's environment
 * @returns {{ child: import('child_process').ChildProcess, exited: Promise<number> }}
 */
function startCommand(command, cwd, onLine, env = {}) {
    const child = spawn(bashPath(), ['-c', command], {
        cwd, env: commandEnv(env), detached: true, stdio: ['ignore', 'pipe', 'pipe'],
    });
    for (const stream of [child.stdout, child.stderr]) {
        let rest = '';
        stream.setEncoding('utf8');
        stream.on('data', (chunk) => {
            const parts = (rest + chunk).split(/\r?\n|\r/);
            rest = parts.pop() || '';
            for (const line of parts) if (line.trim()) onLine(line);
        });
        stream.on('end', () => { if (rest.trim()) onLine(rest); rest = ''; });
    }
    const exited = new Promise((resolve) => {
        child.on('error', () => resolve(-1));
        child.on('close', (code, signal) => resolve(code === null ? (signal ? 128 : -1) : code));
    });
    return { child, exited: /** @type {Promise<number>} */ (exited) };
}

/** Ends a command's whole process group. @param {import('child_process').ChildProcess} child */
function stopGroup(child) {
    if (!child.pid) return;
    const kill = (/** @type {NodeJS.Signals} */ sig) => {
        try { process.kill(-(/** @type {number} */ (child.pid)), sig); } catch { /* gone */ }
    };
    kill('SIGTERM');
    setTimeout(() => kill('SIGKILL'), 2_000).unref();
}

/** Whether 127.0.0.1:port accepts a connection. @param {number} port */
function portAnswers(port) {
    return new Promise((resolve) => {
        const socket = net.connect({ host: '127.0.0.1', port });
        const done = (/** @type {boolean} */ ok) => { socket.destroy(); resolve(ok); };
        socket.setTimeout(1_000, () => done(false));
        socket.once('connect', () => done(true));
        socket.once('error', () => done(false));
    });
}

const sleep = (/** @type {number} */ ms) => new Promise((r) => setTimeout(r, ms));

/** @param {number} [n] */
function newRunId(n = Date.now()) {
    return 'kit-' + n.toString(36) + '-' + Math.random().toString(36).slice(2, 8);
}

/**
 * @typedef {{
 *   runId: string, recipe: string, project: string,
 *   step: { index: number, total: number, title: string, state: 'running' | 'ok' | 'fail' },
 *   line: string | null,
 *   done: null | { ok: boolean, projectPath: string, message: string },
 *   tail?: string[],
 *   run?: string | null,
 * }} Progress
 */

/**
 * Runs `recipe` into a new project folder. Never throws: every outcome ends
 * in one progress message with `done` set.
 *
 * With `prepare`, it is `kit prepare`: the recipe runs once in
 * `~/.diligent/kit-prepare/<id>` to fill npm's cache (and a wheel folder for
 * pip), without its test step, the folder is deleted at the end, and a run
 * that worked is recorded in `~/.diligent/kit-offline.json`. A later `kit
 * add` of a prepared recipe uses the cache first ([cacheEnv]) and starts
 * without the internet.
 *
 * @param {{
 *   recipe: any, name?: string | null, runId?: string, prepare?: boolean,
 *   extensionDir: string, home: string, projectsDir: string,
 *   onProgress: (p: Progress) => void,
 *   installToolchain: (pack: string) => Promise<unknown>,
 *   checkInternet?: () => Promise<boolean>,
 *   free?: (dir: string) => number | null,
 *   signal?: { cancelled: boolean, onCancel?: () => void },
 *   timeouts?: { toolchainMs?: number, pollMs?: number, runMs?: number },
 *   throttleMs?: number,
 * }} o
 * @returns {Promise<{ ok: boolean, projectPath: string, message: string, run: string | null }>}
 */
async function runRecipe(o) {
    const recipe = o.recipe;
    const runId = o.runId || newRunId();
    const signal = o.signal || { cancelled: false };
    const prepare = Boolean(o.prepare);
    const prepareDir = path.join(o.home, PREPARE_DIR, recipe.id);
    const wheels = path.join(o.home, WHEELS_DIR, recipe.id);
    if (prepare) fs.rmSync(prepareDir, { recursive: true, force: true });
    const name = prepare ? recipe.id : o.name || defaultName(o.projectsDir, recipe.id);
    const folder = prepare ? { dir: prepareDir } : projectFolder(o.projectsDir, name);
    const project = 'dir' in folder ? folder.dir : path.join(o.projectsDir, String(name));
    const vars = { name: String(name), pkg: packageName(String(name)), label: safeLabel(String(name)), project };
    const installed = installedPacks(o.home);
    const prepared = !prepare && recipe.needs.internet === 'once' && readyOffline(recipe, preparedRecipes(o.home), installed);
    const steps = planSteps(recipe, installed, vars);
    const total = steps.length;
    /** @type {string | null} */
    let runHint = null;
    let current = { index: 0, total, title: steps[0].title, state: /** @type {'running' | 'ok' | 'fail'} */ ('running') };
    /** @type {string | null} */
    let lastLine = null;
    /** @type {string[]} */
    let tail = [];

    /** @param {Partial<Progress>} extra */
    const emit = (extra = {}) => o.onProgress({
        runId, recipe: recipe.id, project, step: { ...current }, line: lastLine, done: null, ...extra,
    });
    const throttle = new LineThrottle(() => emit(), o.throttleMs ?? LINE_INTERVAL_MS);
    /** @param {string} line */
    const onLine = (line) => {
        lastLine = line.length > 500 ? line.slice(0, 500) : line;
        tail.push(lastLine);
        if (tail.length > TAIL_LINES) tail.shift();
        throttle.push(lastLine);
    };
    /** @param {boolean} ok @param {string} message */
    const finish = (ok, message) => {
        throttle.clear();
        if (prepare) {
            fs.rmSync(prepareDir, { recursive: true, force: true });
            if (ok) {
                try { markPrepared(o.home, recipe); } catch (err) { ok = false; message = 'Could not record it: ' + String(err); }
            }
        } else if (ok && recipe.needs.internet === 'once' && !usesPip(recipe)) {
            // npm's cache now holds everything this recipe downloads.
            try { markPrepared(o.home, recipe); } catch { /* still a working project */ }
        }
        const done = { ok, projectPath: project, message };
        o.onProgress({ runId, recipe: recipe.id, project, step: { ...current }, line: lastLine, done, run: runHint });
        return { ok, projectPath: project, message, run: runHint };
    };

    if ('error' in folder) {
        current.state = 'fail';
        return finish(false, folder.error);
    }

    // Fail early: everything missing, named, before anything is written.
    const needInternet = needsInternetCheck(recipe, installed);
    const internet = needInternet ? await (o.checkInternet || internetAnswers)() : null;
    fs.mkdirSync(o.projectsDir, { recursive: true });
    const missing = missingNeeds(recipe, { freeMB: (o.free || freeMB)(o.projectsDir), internet, installed, prepared });
    if (missing.length) {
        current.state = 'fail';
        return finish(false, missing.join(' '));
    }

    fs.mkdirSync(project, { recursive: true });
    if (prepare && usesPip(recipe)) {
        fs.rmSync(wheels, { recursive: true, force: true });
        fs.mkdirSync(wheels, { recursive: true });
    }
    const env = prepare ? (usesPip(recipe) ? { PIP_FIND_LINKS: wheels } : {}) : cacheEnv({ prepared, internet, wheels });
    const kept = prepare ? '' : ' The folder ' + project + ' was kept.';
    const templates = path.join(o.extensionDir, 'templates');
    /** @type {any[]} */
    const profiles = [];

    for (let i = 0; i < total; i += 1) {
        const step = steps[i];
        current = { index: i, total, title: step.title, state: 'running' };
        lastLine = null;
        tail = [];
        if (signal.cancelled) return finish(false, 'Setup was cancelled.' + kept);
        emit();
        /** @type {string | null} */
        let failure = null;
        try {
            failure = await runStep(step, {
                o, project, vars, templates, profiles, onLine, signal, env, prepare, wheels,
                setHint: (/** @type {string} */ h) => { runHint = h; },
            });
        } catch (err) {
            failure = err instanceof Error ? err.message : String(err);
        }
        throttle.clear();
        if (signal.cancelled) {
            current.state = 'fail';
            return finish(false, 'Setup was cancelled.' + kept);
        }
        if (failure !== null) {
            current.state = 'fail';
            if (failure) lastLine = failure;
            o.onProgress({ runId, recipe: recipe.id, project, step: { ...current }, line: lastLine, done: null, tail });
            return finish(false, 'Step ' + (i + 1) + ' of ' + total + ' failed: ' + step.title + '. ' +
                (failure || 'See the output above.') + kept);
        }
        current.state = 'ok';
        emit();
    }
    return finish(true, prepare ? recipe.title + ' is ready offline: kit add ' + recipe.id + ' now works without the internet.'
        : recipe.title + ' is ready in ' + project + '.');
}

/**
 * One step. Resolves with null when it worked and with the reason (possibly
 * an empty string when the output says it) when it did not.
 * @param {any} step
 * @param {any} c
 * @returns {Promise<string | null>}
 */
async function runStep(step, c) {
    const { o, project, vars, templates, profiles, onLine, signal, env } = c;
    if (step.type === 'toolchain') {
        const label = PACKS[/** @type {keyof PACKS} */ (step.pack)].label;
        if (step.alreadyDone || installedPacks(o.home).has(step.pack)) {
            onLine('The ' + label + ' pack is already installed.');
            return null;
        }
        try {
            await o.installToolchain(step.pack);
        } catch (err) {
            return 'Could not ask the app to install the ' + label + ' pack (' +
                (err instanceof Error ? err.message : String(err)) + '). Install it from Toolchains and try again.';
        }
        // The bridge method only starts the install and answers nothing more:
        // the app reports progress to its own screen. Its record of installed
        // packs is the one thing this side can read, so it is polled.
        const started = Date.now();
        const limit = o.timeouts?.toolchainMs ?? TOOLCHAIN_TIMEOUT_MS;
        const poll = o.timeouts?.pollMs ?? TOOLCHAIN_POLL_MS;
        while (!installedPacks(o.home).has(step.pack)) {
            if (signal.cancelled) return 'cancelled';
            if (Date.now() - started > limit) {
                return 'The ' + label + ' pack did not finish installing in ' + Math.round(limit / 60_000) +
                    ' minutes. Check Toolchains for an error, then try again.';
            }
            onLine('Installing the ' + label + ' pack (' + Math.round((Date.now() - started) / 1000) + ' s)');
            await sleep(poll);
        }
        onLine('The ' + label + ' pack is installed.');
        return null;
    }
    if (step.type === 'write') {
        const source = path.join(templates, step.template);
        const target = path.join(project, render(step.path, vars));
        const text = fs.readFileSync(source, 'utf8');
        fs.mkdirSync(path.dirname(target), { recursive: true });
        fs.writeFileSync(target, render(text, vars));
        onLine('Wrote ' + path.relative(project, target));
        return null;
    }
    if (step.type === 'profile') {
        const list = step.profiles.map((/** @type {any} */ p) => ({ ...p, command: render(p.command, shellVars(vars)) }));
        profiles.push(...list);
        const file = path.join(project, '.diligent', 'run.json');
        fs.mkdirSync(path.dirname(file), { recursive: true });
        fs.writeFileSync(file, runJson(profiles));
        c.setHint(list[0].command);
        onLine('Wrote .diligent/run.json');
        return null;
    }
    if (step.type === 'run') {
        if (c.prepare && PIP_INSTALL.test(step.command)) {
            const failed = await runCommand(pipDownload(render(step.command, shellVars(vars)), c.wheels), c);
            if (failed !== null) return failed;
        }
        return runCommand(render(step.command, shellVars(vars)), c, step.timeoutSec);
    }
    if (c.prepare) {
        // test: nothing it does fills a cache, and its port may be taken.
        onLine('Skipped while preparing.');
        return null;
    }
    // test: start the profile, wait for its port or an expected line, stop it.
    return runTest(step, c);
}

/**
 * Runs one command of a `run` step in the project folder.
 * @param {string} command @param {any} c @param {number} [timeoutSec]
 * @returns {Promise<string | null>}
 */
async function runCommand(command, c, timeoutSec) {
    const { o, project, onLine, signal, env } = c;
    const { child, exited } = startCommand(command, project, onLine, env);
    signal.onCancel = () => stopGroup(child);
    const limit = (timeoutSec ? timeoutSec * 1000 : (o.timeouts?.runMs ?? RUN_TIMEOUT_MS));
    const timer = setTimeout(() => stopGroup(child), limit);
    const code = await exited;
    clearTimeout(timer);
    signal.onCancel = undefined;
    if (code === 0) return null;
    return code === 128 ? 'It was stopped after ' + Math.round(limit / 1000) + ' s.' : 'It ended with exit code ' + code + '.';
}

/**
 * The `test` step: start the profile, wait for its port or an expected line, stop it.
 * @param {any} step @param {any} c
 * @returns {Promise<string | null>}
 */
async function runTest(step, c) {
    const { project, profiles, onLine, signal, env } = c;
    const profile = profiles.find((/** @type {any} */ p) => p.name === step.profile);
    const expect = step.expect;
    if (expect.port !== undefined && await portAnswers(expect.port)) {
        onLine('Port ' + expect.port + ' is already in use by something else, so the test was skipped.');
        return null;
    }
    const pattern = expect.output !== undefined ? new RegExp(expect.output) : null;
    let matched = false;
    const { child, exited } = startCommand(profile.command, project, (line) => {
        if (pattern && pattern.test(line)) matched = true;
        onLine(line);
    }, env);
    signal.onCancel = () => stopGroup(child);
    let ended = false;
    /** @type {number | null} */
    let exitCode = null;
    exited.then((code) => { ended = true; exitCode = code; });
    const limit = step.timeoutSec ? step.timeoutSec * 1000 : TEST_TIMEOUT_MS;
    const started = Date.now();
    try {
        for (;;) {
            if (pattern && matched && (expect.port === undefined)) return null;
            if (expect.port !== undefined && await portAnswers(expect.port) && (!pattern || matched)) {
                onLine('It answered on port ' + expect.port + '.');
                return null;
            }
            if (ended) {
                // A script that printed what was expected and ended is a pass.
                await sleep(50);
                if (pattern && matched && expect.port === undefined) return null;
                return 'It ended (exit code ' + exitCode + ') before ' +
                    (expect.port !== undefined ? 'answering on port ' + expect.port : 'printing what was expected') + '.';
            }
            if (signal.cancelled) return 'cancelled';
            if (Date.now() - started > limit) {
                return 'It did not ' + (expect.port !== undefined ? 'answer on port ' + expect.port : 'print what was expected') +
                    ' within ' + Math.round(limit / 1000) + ' s.';
            }
            await sleep(300);
        }
    } finally {
        signal.onCancel = undefined;
        if (!ended) stopGroup(child);
    }
}

/** The placeholders for shell text, quoted. @param {Record<string, string>} vars */
function shellVars(vars) {
    /** @type {Record<string, string>} */
    const out = {};
    for (const [k, v] of Object.entries(vars)) out[k] = shellQuote(v);
    return out;
}

/** HOME and the projects folder the terminal uses. */
function locations() {
    const home = process.env.HOME || os.homedir();
    const projectsDir = process.env.PROJECTS_DIR || path.join(home, 'projects');
    return { home, projectsDir };
}

module.exports = {
    STEP_TYPES,
    INTERNET,
    PACKS,
    LINE_INTERVAL_MS,
    nameProblem,
    packageName,
    safeLabel,
    shellQuote,
    render,
    recipeProblems,
    safeRelative,
    loadRecipes,
    summaries,
    OFFLINE_FILE,
    PREPARE_DIR,
    WHEELS_DIR,
    preparedRecipes,
    markPrepared,
    readyOffline,
    usesPip,
    pipDownload,
    cacheEnv,
    missingNeeds,
    needsInternetCheck,
    freeMB,
    internetAnswers,
    installedPacks,
    packId,
    planSteps,
    projectFolder,
    defaultName,
    runJson,
    stepLine,
    LineThrottle,
    runRecipe,
    newRunId,
    portAnswers,
    locations,
};
