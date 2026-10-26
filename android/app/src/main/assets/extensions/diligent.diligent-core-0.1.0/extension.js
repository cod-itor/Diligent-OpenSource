// @ts-check
'use strict';

/**
 * Diligent Core (Node extension host)
 *
 * Phase 2's reliability work, where it can see processes and files:
 *
 * - The supervisor. Every run started from Diligent leaves its launch script
 *   in `~/.diligent/runs/<session>.sh` (Kotlin's RunScripts). Every 10 s each
 *   one is checked: is its tmux session alive, and does its port answer? A run
 *   that ended is started again by running that script, after 1 s, 5 s, then
 *   30 s, and given up after 5 restarts in 10 minutes, with the cause named. A
 *   run the user stopped (status file `stopped`) is never restarted. All state
 *   that matters lives in files and tmux, so after an editor server restart
 *   the next check finds the same runs and nothing is started twice (the
 *   launch script exits early when its session is alive).
 *
 * - The process budget. The count comes from process-monitor.js's snapshot;
 *   a warning is shown once each time it reaches 24.
 *
 * - Snapshots of the open project on `refs/diligent/snapshots` (snapshots.js).
 *
 * The app hears about runs through the bridge method `diligentUpdateTasks`.
 * This host has no way to the page's BroadcastChannel, so the call goes
 * through the `diligent.bridge.updateTasks` command that the web-side
 * diligent-bridge extension registers for each name in its BRIDGE_METHODS,
 * which forwards it over VSCodroid's relay to `AndroidBridge.diligentUpdateTasks`.
 *
 * Every program runs through execFile with an argv array: no value (a session
 * name, a path, a reason) is ever put into shell text.
 */

const vscode = require('vscode');
const fs = require('fs');
const net = require('net');
const os = require('os');
const path = require('path');
const { execFile } = require('child_process');
const sup = require('./supervisor');
const snapshots = require('./snapshots');

const UPDATE_COMMAND = 'diligent.bridge.updateTasks';

/** Sent again unchanged this often, so an app that restarted catches up. */
const RESEND_EVERY_CHECKS = 6;

const PORT_TIMEOUT_MS = 2_000;
const LOG_TAIL_BYTES = 64 * 1024;

const home = process.env.HOME || os.homedir();
const runsDir = path.join(home, '.diligent', 'runs');
const logDir = path.join(home, '.diligent', 'logs');
const stateFile = path.join(runsDir, 'supervisor.json');
const processSnapshot = path.join(process.env.TMPDIR || os.tmpdir(), 'vscodroid-processes.json');

/** The terminal's bash when the app set one, which is what Kotlin runs the scripts with. */
const bash = process.env.SHELL && path.basename(process.env.SHELL) === 'bash' ? process.env.SHELL : 'bash';

/**
 * @param {string} file
 * @param {string[]} args
 * @param {{cwd?: string, env?: Record<string, string>, timeout?: number}} [options]
 * @returns {Promise<{code: number, stdout: string, stderr: string}>}
 */
function run(file, args, options = {}) {
    return new Promise((resolve) => {
        execFile(file, args, {
            cwd: options.cwd,
            env: options.env ? { ...process.env, ...options.env } : process.env,
            timeout: options.timeout || 60_000,
            maxBuffer: 16 * 1024 * 1024,
        }, (err, stdout, stderr) => {
            const code = err ? (typeof (/** @type {any} */ (err)).code === 'number' ? /** @type {any} */ (err).code : -1) : 0;
            resolve({ code, stdout: String(stdout), stderr: String(stderr || (err && code === -1 ? err.message : '')) });
        });
    });
}

/** @param {string} file @returns {string | null} */
function readText(file) {
    try {
        return fs.readFileSync(file, 'utf8');
    } catch {
        return null;
    }
}

/** The last lines of a file, reading only its end. @param {string} file */
function tailLines(file) {
    try {
        const fd = fs.openSync(file, 'r');
        try {
            const size = fs.fstatSync(fd).size;
            const start = Math.max(0, size - LOG_TAIL_BYTES);
            const buffer = Buffer.alloc(size - start);
            fs.readSync(fd, buffer, 0, buffer.length, start);
            const lines = buffer.toString('utf8').split('\n').map((l) => l.replace(/\r$/, ''));
            if (lines.length && lines[lines.length - 1] === '') lines.pop();
            return lines;
        } finally {
            fs.closeSync(fd);
        }
    } catch {
        return [];
    }
}

/** Whether something accepts a TCP connection on 127.0.0.1:port. @param {number} port */
function portAnswers(port) {
    return new Promise((resolve) => {
        const socket = net.connect({ host: '127.0.0.1', port });
        const done = (/** @type {boolean} */ ok) => {
            socket.destroy();
            resolve(ok);
        };
        socket.setTimeout(PORT_TIMEOUT_MS, () => done(false));
        socket.once('connect', () => done(true));
        socket.once('error', () => done(false));
    });
}

/**
 * The tmux sessions alive now, or null when tmux could not be asked (missing),
 * in which case nothing is decided this check.
 */
async function liveSessions() {
    const r = await run('tmux', ['list-sessions', '-F', '#{session_name}'], { timeout: 10_000 });
    if (r.code === 0) return new Set(r.stdout.split('\n').filter(Boolean));
    // No server running is the ordinary "no sessions" answer.
    if (/no server running|error connecting|No such file/i.test(r.stderr)) return new Set();
    return null;
}

/**
 * @typedef {{
 *   id: string, name: string, project: string, dir: string, port: number | null,
 *   state: 'running' | 'restarting' | 'failed' | 'stopped',
 *   cause: string | null, message: string | null,
 *   restarts: number, portOk: boolean, portFailures: number,
 *   timer: NodeJS.Timeout | null,
 * }} Task
 */

class Supervisor {
    constructor() {
        /** @type {Map<string, Task>} */
        this.tasks = new Map();
        /** @type {Record<string, {history: number[], failed: {cause: string | null, message: string} | null}>} */
        this.saved = this.load();
        /** @type {Array<{totalKb: number, availableKb: number}>} */
        this.memory = [];
        /** @type {Array<number>} */
        this.processHistory = [];
        /** @type {number | null} */
        this.processes = null;
        this.lastSent = '';
        this.checks = 0;
        this.busy = false;
        this.updateFailed = false;
        this.disposed = false;
    }

    load() {
        try {
            const data = JSON.parse(readText(stateFile) || '{}');
            return data && typeof data === 'object' ? data : {};
        } catch {
            return {};
        }
    }

    persist() {
        try {
            fs.mkdirSync(runsDir, { recursive: true });
            fs.writeFileSync(stateFile + '.tmp', JSON.stringify(this.saved));
            fs.renameSync(stateFile + '.tmp', stateFile);
        } catch (err) {
            console.warn('[Diligent] could not save the supervisor state: ' + String(err));
        }
    }

    /** @param {string} id */
    record(id) {
        if (!this.saved[id]) this.saved[id] = { history: [], failed: null };
        return this.saved[id];
    }

    /** One check of every run; overlapping checks are skipped. */
    async check() {
        if (this.busy || this.disposed) return;
        this.busy = true;
        try {
            await this.checkOnce();
        } catch (err) {
            console.warn('[Diligent] supervisor check failed: ' + String(err));
        } finally {
            this.busy = false;
        }
    }

    async checkOnce() {
        const now = Date.now();
        this.sampleMachine(now);
        const sessions = await liveSessions();
        let files = [];
        try {
            files = fs.readdirSync(runsDir);
        } catch {
            files = [];
        }
        const seen = new Set();
        for (const file of files) {
            const id = sup.sessionOfRunFile(file);
            if (!id) continue;
            seen.add(id);
            if (sessions) await this.checkTask(id, path.join(runsDir, file), sessions, now);
        }
        // A run file that is gone (stopped, finished, removed by hand) ends its task.
        for (const [id, task] of this.tasks) {
            if (!seen.has(id)) {
                if (task.timer) clearTimeout(task.timer);
                this.tasks.delete(id);
            }
        }
        for (const id of Object.keys(this.saved)) {
            if (!seen.has(id)) delete this.saved[id];
        }
        this.persist();
        this.checks += 1;
        this.send(this.checks % RESEND_EVERY_CHECKS === 0);
    }

    /** @param {number} now */
    sampleMachine(now) {
        const mem = sup.parseMemInfo(readText('/proc/meminfo') || '');
        // The last three checks: memory comes back once the killer has done
        // its work, so the lowest recent reading is the one that explains it.
        if (mem) this.memory = [...this.memory, mem].slice(-3);
        const previous = this.processes;
        this.processes = sup.processCount(readText(processSnapshot), now);
        if (this.processes != null) this.processHistory = [...this.processHistory, this.processes].slice(-3);
        if (sup.crossedProcessWarning(previous, this.processes)) {
            vscode.window.showWarningMessage(vscode.l10n.t(
                'Diligent is using {0} of Android\'s {1} background processes. Close terminals or editors you do not need, or a running task may be closed.',
                String(this.processes), String(sup.PROCESS_LIMIT)));
        }
    }

    lowestMemory() {
        return this.memory.reduce((/** @type {{totalKb: number, availableKb: number} | null} */ low, m) =>
            (low == null || m.availableKb < low.availableKb ? m : low), null);
    }

    highestProcesses() {
        return this.processHistory.length ? Math.max(...this.processHistory) : null;
    }

    /**
     * @param {string} id
     * @param {string} runFile
     * @param {Set<string>} sessions
     * @param {number} now
     */
    async checkTask(id, runFile, sessions, now) {
        const meta = sup.parseRunFile(readText(runFile) || '');
        if (!meta) return;
        let task = this.tasks.get(id);
        if (!task) {
            task = {
                id, name: meta.name, project: path.basename(meta.dir), dir: meta.dir, port: meta.port,
                state: 'running', cause: null, message: null, restarts: 0,
                portOk: false, portFailures: 0, timer: null,
            };
            this.tasks.set(id, task);
        }
        const record = this.record(id);
        task.restarts = sup.recentRestarts(record.history, now).length;
        const runJson = readText(path.join(meta.dir, '.diligent', 'run.json'));
        task.port = (runJson && sup.portFromRunJson(runJson, meta.name)) || meta.port;
        if (task.timer) return; // a restart is already waiting

        const alive = sessions.has(id);
        const status = sup.parseStatus(readText(path.join(logDir, id + '.status')));
        let age = Infinity;
        try {
            age = now - fs.statSync(runFile).mtimeMs;
        } catch { /* gone meanwhile */ }
        const look = sup.classify({ alive, status, runFileAgeMs: age });

        if (look === 'running') {
            if (record.failed) {
                // Started again by the user after Diligent gave up: a fresh start.
                record.failed = null;
                record.history = [];
                task.restarts = 0;
            }
            task.state = 'running';
            if (task.port == null) {
                task.cause = null;
                task.message = null;
            } else if (await portAnswers(task.port)) {
                task.portOk = true;
                task.portFailures = 0;
                task.cause = null;
                task.message = null;
            } else if (task.portOk) {
                task.portFailures += 1;
                if (task.portFailures >= sup.PORT_FAILURES_TO_RESTART) {
                    await run(bash, ['-c', sup.KILL_SCRIPT, 'bash', id], { timeout: 10_000 });
                    this.failed(task, record, null,
                        '"' + task.name + '" stopped answering on port ' + task.port + '.', now);
                }
            }
            return;
        }
        if (look === 'starting') return;
        if (look === 'stopped' || look === 'finished') {
            // Stopped by the user, or done: never restarted.
            this.forget(id, runFile);
            return;
        }
        if (record.failed) {
            task.state = 'failed';
            task.cause = record.failed.cause;
            task.message = record.failed.message;
            return;
        }
        const log = tailLines(path.join(logDir, id + '.log'));
        const marker = sup.exitMarker(log);
        const named = sup.nameCause({
            name: task.name,
            exitCode: marker != null ? marker : (status && status.kind === 'exit' ? status.code : null),
            memory: this.lowestMemory(),
            processes: this.highestProcesses(),
            log,
            port: task.port,
        });
        this.failed(task, record, named.cause, named.message, now);
    }

    /**
     * A run ended or hangs: schedule its restart, or give up.
     * @param {Task} task
     * @param {{history: number[], failed: {cause: string | null, message: string} | null}} record
     * @param {string | null} cause
     * @param {string} message
     * @param {number} now
     */
    failed(task, record, cause, message, now) {
        const next = sup.nextRestart(record.history, now);
        task.cause = cause;
        task.portOk = false;
        task.portFailures = 0;
        if (next.giveUp) {
            task.state = 'failed';
            task.message = sup.withOutcome(message, 'failed');
            record.failed = { cause, message: task.message };
            return;
        }
        task.state = 'restarting';
        task.message = sup.withOutcome(message, 'restarting');
        if (this.disposed) return;
        task.timer = setTimeout(() => {
            task.timer = null;
            this.restart(task).catch((err) => console.warn('[Diligent] restart failed: ' + String(err)));
        }, next.delayMs);
    }

    /** @param {Task} task */
    async restart(task) {
        const runFile = path.join(runsDir, task.id + '.sh');
        // Stop may have landed during the wait (Kotlin writes `stopped` just after the session ends).
        const status = sup.parseStatus(readText(path.join(logDir, task.id + '.status')));
        if (status && status.kind === 'stopped') {
            this.forget(task.id, runFile);
            this.send(false);
            return;
        }
        if (!fs.existsSync(runFile)) return;
        const record = this.record(task.id);
        record.history = [...sup.recentRestarts(record.history, Date.now()), Date.now()];
        task.restarts = record.history.length;
        this.persist();
        const r = await run(bash, [runFile], { cwd: home, timeout: 30_000 });
        if (r.code !== 0) console.warn('[Diligent] restarting ' + task.id + ' answered ' + r.code + ': ' + r.stdout + r.stderr);
        this.send(false);
    }

    /** @param {string} id @param {string} runFile */
    forget(id, runFile) {
        const task = this.tasks.get(id);
        if (task && task.timer) clearTimeout(task.timer);
        this.tasks.delete(id);
        delete this.saved[id];
        try {
            fs.unlinkSync(runFile);
        } catch { /* already gone */ }
    }

    /** Stops a run as Kotlin's Stop does, and forgets it. @param {unknown} id */
    async stop(id) {
        if (!sup.isSessionName(id)) throw new Error('not a Diligent task: ' + String(id));
        const session = /** @type {string} */ (id);
        const task = this.tasks.get(session);
        if (task && task.timer) {
            clearTimeout(task.timer);
            task.timer = null;
        }
        await run(bash, ['-c', sup.STOP_SCRIPT, 'bash', session,
            path.join(logDir, session + '.status'), path.join(logDir, session + '.log'), logDir], { timeout: 15_000 });
        this.forget(session, path.join(runsDir, session + '.sh'));
        this.persist();
        this.send(false);
    }

    /** Tells the app, when something changed or `force`. @param {boolean} force */
    send(force) {
        const payload = sup.updatePayload([...this.tasks.values()], this.processes);
        if (!force && payload === this.lastSent) return;
        this.lastSent = payload;
        Promise.resolve(vscode.commands.executeCommand(UPDATE_COMMAND, payload)).then(() => {
            this.updateFailed = false;
        }, (err) => {
            // Logged once per outage: the web side may not be up yet.
            if (!this.updateFailed) console.warn('[Diligent] could not send the task list: ' + String(err));
            this.updateFailed = true;
            this.lastSent = '';
        });
    }

    dispose() {
        this.disposed = true;
        for (const task of this.tasks.values()) if (task.timer) clearTimeout(task.timer);
    }
}

// ---- snapshots ------------------------------------------------------------

/** globalState key holding the folders whose owner declined `git init`. */
const DECLINED_KEY = 'diligent.snapshot.declinedInit';

/** Left out of snapshots in a repository Diligent created. */
const FRESH_EXCLUDES = ['node_modules/', '.venv/', 'venv/', '__pycache__/', '.next/', 'dist/', 'build/'];

/** @param {string} cwd */
function gitIn(cwd) {
    /** @type {import('./snapshots').Git} */
    return (args, options = {}) => run('git', args, { cwd, env: options.env, timeout: 120_000 });
}

/** The open project's folder, or null. */
function projectFolder() {
    const folders = vscode.workspace.workspaceFolders;
    return folders && folders.length > 0 ? folders[0].uri.fsPath : null;
}

/**
 * Makes sure `folder` is a Git repository, asking once before `git init`.
 * @param {string} folder
 * @param {import('vscode').Memento} state
 */
async function ensureRepository(folder, state) {
    const git = gitIn(folder);
    if (await snapshots.gitDirOf(git)) return true;
    /** @type {string[]} */
    const declined = state.get(DECLINED_KEY) || [];
    if (declined.includes(folder)) return false;
    const yes = vscode.l10n.t('Turn on snapshots');
    const answer = await vscode.window.showInformationMessage(
        vscode.l10n.t('Diligent can save snapshots of this project so you can undo changes. This makes the folder a Git repository. Turn on snapshots?'),
        yes, vscode.l10n.t('Not now'));
    if (answer !== yes) {
        await state.update(DECLINED_KEY, [...declined, folder]);
        return false;
    }
    const r = await git(['init']);
    if (r.code !== 0) return false;
    // A folder that was not a repository has no .gitignore written for Git
    // yet, and the dependency folders would make every snapshot huge. The
    // repository is the one just made, so its own exclude file is ours to set.
    const gitDir = await snapshots.gitDirOf(git);
    if (gitDir) {
        try {
            fs.mkdirSync(path.join(gitDir, 'info'), { recursive: true });
            fs.appendFileSync(path.join(gitDir, 'info', 'exclude'), FRESH_EXCLUDES.join('\n') + '\n');
        } catch (err) {
            console.warn('[Diligent] could not write the exclude file: ' + String(err));
        }
    }
    return true;
}

/**
 * Takes a snapshot of the open project. Answers the commit, or null when there
 * is no project, the user declined, or nothing changed.
 * @param {unknown} reason
 * @param {import('vscode').Memento} state
 */
async function takeSnapshot(reason, state) {
    const folder = projectFolder();
    if (!folder || !(await ensureRepository(folder, state))) return null;
    return snapshots.take(gitIn(folder), snapshots.cleanReason(reason));
}

async function showSnapshots() {
    const folder = projectFolder();
    if (!folder) {
        vscode.window.showInformationMessage(vscode.l10n.t('Open a project to see its snapshots.'));
        return;
    }
    const git = gitIn(folder);
    const entries = (await snapshots.gitDirOf(git)) ? await snapshots.list(git) : [];
    if (entries.length === 0) {
        vscode.window.showInformationMessage(vscode.l10n.t('There are no snapshots of this project yet. Diligent takes one every 10 minutes while you save files.'));
        return;
    }
    const picked = await vscode.window.showQuickPick(entries.map((e) => ({
        label: new Date(e.time).toLocaleString(),
        description: e.reason,
        commit: e.commit,
    })), { placeHolder: vscode.l10n.t('Pick a snapshot to restore') });
    if (!picked) return;
    const restore = vscode.l10n.t('Restore');
    const answer = await vscode.window.showWarningMessage(
        vscode.l10n.t('Restore the project files to how they were at {0}? Diligent saves a snapshot of the current files first, so you can come back.', picked.label),
        { modal: true }, restore);
    if (answer !== restore) return;
    await snapshots.restore(git, picked.commit);
    vscode.window.showInformationMessage(vscode.l10n.t('Files restored to {0}.', picked.label));
}

/** @param {import('vscode').ExtensionContext} context */
function activate(context) {
    const supervisor = new Supervisor();
    const timer = setInterval(() => supervisor.check(), sup.CHECK_INTERVAL_MS);
    supervisor.check();

    let savedSinceSnapshot = false;
    let snapshotting = false;
    const snapshotTimer = setInterval(() => {
        if (!savedSinceSnapshot || snapshotting) return;
        savedSinceSnapshot = false;
        snapshotting = true;
        takeSnapshot('auto', context.globalState)
            .catch((err) => console.warn('[Diligent] snapshot failed: ' + String(err)))
            .finally(() => { snapshotting = false; });
    }, snapshots.INTERVAL_MS);

    /** @param {(...a: any[]) => Promise<unknown>} fn */
    const reported = (fn) => (/** @type {any[]} */ ...a) => fn(...a).catch((/** @type {unknown} */ err) => {
        vscode.window.showErrorMessage(vscode.l10n.t('Diligent: {0}', err instanceof Error ? err.message : String(err)));
    });

    context.subscriptions.push(
        { dispose: () => { clearInterval(timer); clearInterval(snapshotTimer); supervisor.dispose(); } },
        vscode.workspace.onDidSaveTextDocument((doc) => {
            if (doc.uri.scheme !== 'output') savedSinceSnapshot = true;
        }),
        vscode.commands.registerCommand('diligent.core.stopTask', reported((id) => supervisor.stop(id))),
        vscode.commands.registerCommand('diligent.snapshot.take',
            reported((reason) => takeSnapshot(reason, context.globalState))),
        vscode.commands.registerCommand('diligent.snapshot.showList', reported(() => showSnapshots())),
    );
}

function deactivate() {}

module.exports = { activate, deactivate, Supervisor };
