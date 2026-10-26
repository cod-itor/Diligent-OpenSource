// @ts-check
'use strict';

/**
 * The supervisor's decisions, with no I/O: what a run file says, what the
 * status file and the log say about how a run ended, why it ended, how long to
 * wait before starting it again, and when to stop trying. extension.js does
 * the reading, the tmux calls and the restarts; scripts/test-diligent-core.js
 * tests everything here.
 *
 * The files it reads are written by Kotlin's RunScripts (diligent/run/):
 *
 * - `~/.diligent/runs/<session>.sh`: the launch script of a run started from
 *   Diligent. Its first line is `# diligent-run: {"dir":...,"name":...,"port":...}`.
 *   Running it with bash starts the session again, or does nothing when the
 *   session is alive, so a restart can never make a duplicate.
 * - `~/.diligent/logs/<session>.status`: `exit N` when the command ended by
 *   itself, `stopped` when the user stopped it.
 * - `~/.diligent/logs/<session>.log`: ends with `DILIGENT_EXIT:<code>` when
 *   the command ended by itself. No marker and no session means the session
 *   was killed from outside.
 */

/** How often every run is checked. */
const CHECK_INTERVAL_MS = 10_000;

/** Waits before the first, second and every later restart within the window. */
const BACKOFF_MS = Object.freeze([1_000, 5_000, 30_000]);

/** At most this many restarts within RESTART_WINDOW_MS, then the run is given up. */
const MAX_RESTARTS = 5;
const RESTART_WINDOW_MS = 10 * 60 * 1000;

/**
 * A port that answered before and then failed this many checks in a row
 * belongs to a server that hangs: it is restarted. A port that never answered
 * is a server still starting (a first Next.js build takes minutes on a phone),
 * or one listening elsewhere, and is left alone.
 */
const PORT_FAILURES_TO_RESTART = 3;

/**
 * A run file this new, with no session and no status yet, is a start in
 * progress (Kotlin writes the file just before tmux creates the session).
 */
const START_GRACE_MS = 15_000;

/** Android's system-wide phantom process limit, as process-monitor.js names it. */
const PROCESS_LIMIT = 32;

/** Warn the user from this many processes. */
const PROCESS_WARN = 24;

/** A kill with at least this many processes is put down to the process limit. */
const PROCESS_NEAR_LIMIT = 28;

/** MemAvailable below this many kB, or below a tenth of MemTotal, is low memory. */
const LOW_MEMORY_KB = 200 * 1024;

/** How many log lines an exit message carries. */
const EXIT_LOG_LINES = 20;

const SESSION = /^diligent-[a-z0-9_-]+$/;
const RUN_META = '# diligent-run: ';
const EXIT_LINE = /^DILIGENT_EXIT:(-?\d+)\s*$/;

/**
 * The session a run file is named for, or null for a file that is not one of
 * ours. The name goes to tmux and into file paths, so anything outside the
 * characters RunSession produces is refused.
 * @param {string} fileName
 * @returns {string | null}
 */
function sessionOfRunFile(fileName) {
    if (!fileName.endsWith('.sh')) return null;
    const session = fileName.slice(0, -3);
    return SESSION.test(session) ? session : null;
}

/** @param {unknown} id */
function isSessionName(id) {
    return typeof id === 'string' && SESSION.test(id);
}

/**
 * The metadata line of a launch script: the project folder, the profile name
 * and its port. Null when the first line is not ours or does not parse.
 * @param {string} text
 * @returns {{dir: string, name: string, port: number | null} | null}
 */
function parseRunFile(text) {
    const first = text.split('\n', 1)[0];
    if (!first.startsWith(RUN_META)) return null;
    let meta;
    try {
        meta = JSON.parse(first.slice(RUN_META.length));
    } catch {
        return null;
    }
    if (!meta || typeof meta.dir !== 'string' || typeof meta.name !== 'string') return null;
    return { dir: meta.dir, name: meta.name, port: isPort(meta.port) ? meta.port : null };
}

/** @param {unknown} n */
function isPort(n) {
    return typeof n === 'number' && Number.isInteger(n) && n >= 1 && n <= 65535;
}

/**
 * The port a profile has in the project's `.diligent/run.json` now, which wins
 * over the one the run file recorded at start: a user who changed the port
 * restarts to get it. Null when the file or the profile is not there.
 * @param {string} runJson
 * @param {string} profileName
 * @returns {number | null}
 */
function portFromRunJson(runJson, profileName) {
    let config;
    try {
        config = JSON.parse(runJson);
    } catch {
        return null;
    }
    const profiles = config && Array.isArray(config.profiles) ? config.profiles : [];
    const profile = profiles.find((/** @type {any} */ p) => p && typeof p.name === 'string' && p.name.trim() === profileName);
    if (!profile) return null;
    const raw = typeof profile.port === 'string' && /^\d+$/.test(profile.port.trim()) ? Number(profile.port.trim()) : profile.port;
    return isPort(raw) ? raw : null;
}

/**
 * The status file's first line.
 * @param {string | null} text
 * @returns {{kind: 'stopped'} | {kind: 'exit', code: number} | null}
 */
function parseStatus(text) {
    if (text == null) return null;
    const line = text.split('\n', 1)[0].trim();
    if (line === 'stopped') return { kind: 'stopped' };
    const m = /^exit (-?\d+)$/.exec(line);
    return m ? { kind: 'exit', code: Number(m[1]) } : null;
}

/**
 * The code of the last `DILIGENT_EXIT:<code>` line, or null when the log has
 * none: the session went away without its command ending.
 * @param {string[]} lines
 * @returns {number | null}
 */
function exitMarker(lines) {
    for (let i = lines.length - 1; i >= 0; i -= 1) {
        const m = EXIT_LINE.exec(lines[i]);
        if (m) return Number(m[1]);
    }
    return null;
}

/**
 * `/proc/meminfo` in kB.
 * @param {string} text
 * @returns {{totalKb: number, availableKb: number} | null}
 */
function parseMemInfo(text) {
    const total = /^MemTotal:\s+(\d+)/m.exec(text);
    const available = /^MemAvailable:\s+(\d+)/m.exec(text);
    if (!total || !available) return null;
    return { totalKb: Number(total[1]), availableKb: Number(available[1]) };
}

/** @param {{totalKb: number, availableKb: number} | null} mem */
function isLowMemory(mem) {
    if (!mem) return false;
    return mem.availableKb < LOW_MEMORY_KB || mem.availableKb < mem.totalKb / 10;
}

/**
 * The process count in process-monitor.js's snapshot, or null when there is
 * none or it is too old to be about now (three of the monitor's 10 s scans).
 * @param {string | null} text
 * @param {number} now
 * @returns {number | null}
 */
function processCount(text, now) {
    if (!text) return null;
    let snapshot;
    try {
        snapshot = JSON.parse(text);
    } catch {
        return null;
    }
    if (!snapshot || typeof snapshot.total !== 'number') return null;
    if (!(now - snapshot.timestamp <= 3 * CHECK_INTERVAL_MS)) return null;
    return snapshot.total;
}

/**
 * Whether the count has just reached the warning level. Warned once per
 * crossing: it has to fall below the level before it warns again.
 * @param {number | null} previous
 * @param {number | null} current
 */
function crossedProcessWarning(previous, current) {
    return current != null && current >= PROCESS_WARN && (previous == null || previous < PROCESS_WARN);
}

/** @param {string} name @param {string} what */
function sentence(name, what) {
    return '"' + name + '" ' + what;
}

/**
 * Why a run that is no longer there ended, and a sentence a beginner can act on.
 *
 * In order: a SIGKILL (137) while memory was low is the low memory killer; a
 * kill while the app was near Android's process limit is the phantom process
 * killer; EADDRINUSE in the log is a busy port; a session that vanished
 * without the exit marker was killed from outside; anything else is the
 * command's own exit, shown with the end of its log.
 *
 * @param {{
 *   name: string,
 *   exitCode: number | null,
 *   memory: {totalKb: number, availableKb: number} | null,
 *   processes: number | null,
 *   log: string[],
 *   port: number | null,
 * }} facts exitCode is the marker's (null: no marker)
 * @returns {{cause: 'low-memory' | 'process-limit' | 'port-busy' | 'killed' | 'exit', message: string}}
 */
function nameCause(facts) {
    const { name, exitCode, memory, processes, log, port } = facts;
    const killed = exitCode == null;
    if ((exitCode === 137 || killed) && isLowMemory(memory)) {
        return {
            cause: 'low-memory',
            message: sentence(name, 'was closed because the phone ran low on memory. Closing other apps can help.'),
        };
    }
    if ((exitCode === 137 || killed) && processes != null && processes >= PROCESS_NEAR_LIMIT) {
        return {
            cause: 'process-limit',
            message: sentence(name, 'was closed by Android\'s limit on background processes. Open Health to see how to lift it.'),
        };
    }
    if (log.some((line) => line.includes('EADDRINUSE'))) {
        return {
            cause: 'port-busy',
            message: sentence(name, port != null
                ? 'could not start because another program is already using port ' + port + '. Stop that program or choose another port.'
                : 'could not start because another program is already using its port. Stop that program or choose another port.'),
        };
    }
    if (killed) {
        return {
            cause: 'killed',
            message: sentence(name, 'was closed by Android while it ran in the background.'),
        };
    }
    const tail = log.filter((line) => !EXIT_LINE.test(line)).slice(-EXIT_LOG_LINES);
    return {
        cause: 'exit',
        message: sentence(name, 'stopped with error code ' + exitCode + '.')
            + (tail.length > 0 ? '\n\nLast lines of its output:\n' + tail.join('\n') : ''),
    };
}

/**
 * The restarts that still count: those within the window before `now`.
 * @param {number[]} history restart times
 * @param {number} now
 */
function recentRestarts(history, now) {
    return history.filter((t) => now - t < RESTART_WINDOW_MS && t <= now);
}

/**
 * What to do about a run that failed: wait `delayMs` and restart it, or give
 * up because it was restarted MAX_RESTARTS times within the window already.
 * @param {number[]} history restart times
 * @param {number} now
 * @returns {{giveUp: true} | {giveUp: false, delayMs: number}}
 */
function nextRestart(history, now) {
    const recent = recentRestarts(history, now);
    if (recent.length >= MAX_RESTARTS) return { giveUp: true };
    return { giveUp: false, delayMs: BACKOFF_MS[Math.min(recent.length, BACKOFF_MS.length - 1)] };
}

const RESTARTING = ' Diligent is starting it again.';
const GAVE_UP = ' It stopped ' + MAX_RESTARTS + ' times in 10 minutes, so Diligent stopped restarting it. Open Health to see what can help.';

/**
 * A message with what Diligent does about it added to its first sentence.
 * @param {string} message
 * @param {'restarting' | 'failed'} state
 */
function withOutcome(message, state) {
    const cut = message.indexOf('\n');
    const head = cut < 0 ? message : message.slice(0, cut);
    const rest = cut < 0 ? '' : message.slice(cut);
    return head + (state === 'restarting' ? RESTARTING : GAVE_UP) + rest;
}

/**
 * How the run looks this check, before any restart is decided.
 *
 * @param {{
 *   alive: boolean,
 *   status: {kind: 'stopped'} | {kind: 'exit', code: number} | null,
 *   runFileAgeMs: number,
 * }} look
 * @returns {'running' | 'starting' | 'stopped' | 'finished' | 'ended'}
 *   finished: the command ended by itself with 0; ended: anything that needs
 *   a restart.
 */
function classify(look) {
    if (look.alive) return 'running';
    if (look.status && look.status.kind === 'stopped') return 'stopped';
    if (look.status && look.status.kind === 'exit' && look.status.code === 0) return 'finished';
    if (!look.status && look.runFileAgeMs < START_GRACE_MS) return 'starting';
    return 'ended';
}

/**
 * The JSON the app receives (`diligentUpdateTasks`), in the agreed shape.
 * @param {Array<{id: string, name: string, project: string, state: string, cause?: string | null, message?: string | null, restarts?: number}>} tasks
 * @param {number | null} processes
 */
function updatePayload(tasks, processes) {
    return JSON.stringify({
        tasks: tasks.map((t) => ({
            id: t.id,
            name: t.name,
            project: t.project,
            state: t.state,
            cause: t.cause || null,
            message: t.message || null,
            restarts: t.restarts || 0,
        })),
        processes: processes == null ? null : processes,
        processLimit: PROCESS_LIMIT,
    });
}

/**
 * The bash script that stops a session, the same steps as RunScripts.stop in
 * Kotlin (Ctrl-C, about three seconds, kill, record `stopped`). Every value
 * reaches it as a positional argument, never inside the script text.
 * Run as: bash -c STOP_SCRIPT bash <session> <statusPath> <logPath> <logDir>
 */
const STOP_SCRIPT = [
    's=$1; status=$2; log=$3; dir=$4',
    'if tmux has-session -t "=$s" 2>/dev/null; then',
    '  tmux send-keys -t "=$s:" C-c 2>/dev/null',
    '  i=0',
    '  while [ "$i" -lt 30 ] && tmux has-session -t "=$s" 2>/dev/null; do sleep 0.1; i=$((i + 1)); done',
    '  tmux kill-session -t "=$s" 2>/dev/null',
    'fi',
    'mkdir -p "$dir"',
    'printf \'stopped\\n\' > "$status"',
    'printf \'%s\\n\' \'[diligent] Stopped\' >> "$log"',
    'true',
].join('\n');

/** Kill a hung session so its launch script can start it again. Args: <session>. */
const KILL_SCRIPT = 'tmux kill-session -t "=$1" 2>/dev/null; true';

module.exports = {
    CHECK_INTERVAL_MS,
    BACKOFF_MS,
    MAX_RESTARTS,
    RESTART_WINDOW_MS,
    PORT_FAILURES_TO_RESTART,
    START_GRACE_MS,
    PROCESS_LIMIT,
    PROCESS_WARN,
    PROCESS_NEAR_LIMIT,
    EXIT_LOG_LINES,
    STOP_SCRIPT,
    KILL_SCRIPT,
    sessionOfRunFile,
    isSessionName,
    parseRunFile,
    portFromRunJson,
    parseStatus,
    exitMarker,
    parseMemInfo,
    isLowMemory,
    processCount,
    crossedProcessWarning,
    nameCause,
    recentRestarts,
    nextRestart,
    withOutcome,
    classify,
    updatePayload,
};
