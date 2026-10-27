#!/usr/bin/env node
// @ts-check
'use strict';

/**
 * kit: Diligent's setup command for the terminal.
 *
 *   kit list | kit add <recipe> [name] | kit prepare <recipe> | kit remove <pack>
 *   kit doctor | kit help
 *
 * Shipped by the diligent-kit extension to ~/.diligent/kit/kit.js and run by
 * the `kit()` function in .bashrc. It holds no recipes itself: it asks the
 * engine in the editor over ~/.diligent/kit.sock (newline-delimited JSON, see
 * the extension's server.js). `doctor` works without the engine, apart from
 * the app's health checks.
 *
 * Standalone on purpose: one file, Node's own modules only.
 */

const fs = require('fs');
const net = require('net');
const os = require('os');
const path = require('path');
const { execFileSync } = require('child_process');

const HOME = process.env.HOME || os.homedir();
const SOCKET = path.join(HOME, '.diligent', 'kit.sock');
const PACK_LABELS = { toolchain_ruby: 'Ruby', toolchain_java: 'Java 21', toolchain_cpp: 'C/C++' };

const HELP = [
    'kit: start a working project with one command.',
    '',
    'Usage:',
    '  kit list                  Show what kit can set up',
    '  kit add <recipe> [name]   Create a project from a recipe in your projects folder',
    '  kit prepare <recipe>      Download what a recipe needs now, so kit add works offline later',
    '  kit remove <pack>         Remove a toolchain pack (ruby, java, cpp)',
    '  kit doctor                Check tools, packs, storage and the app\'s health',
    '  kit help                  Show this help',
    '',
    'Example: kit add react-vite my-app',
].join('\n');

const NO_ENGINE = 'The kit engine is not running. Open the editor in Diligent, wait a few seconds, and try again.';

/** @param {string} p */
function tilde(p) {
    return p === HOME || p.startsWith(HOME + '/') ? '~' + p.slice(HOME.length) : p;
}

/** @param {string} value */
function shellQuote(value) {
    return /^[A-Za-z0-9_./~-]+$/.test(value) ? value : "'" + value.replace(/'/g, "'\\''") + "'";
}

/**
 * `kit list` output.
 * @param {Array<{id: string, title: string, summary: string, sizeMB: number, internet: string, toolchains: Array<{label: string}>, offline?: boolean}>} recipes
 */
function formatList(recipes) {
    const width = Math.max(...recipes.map((r) => r.id.length), 4);
    const lines = ['Recipes (kit add <recipe> [name]):', ''];
    for (const r of recipes) {
        const notes = ['about ' + r.sizeMB + ' MB',
            r.internet === 'no' ? 'works offline'
                : r.offline ? 'ready offline'
                    : r.internet === 'once' ? 'needs internet once (kit prepare ' + r.id + ' for offline)' : 'internet for the pack download'];
        if (r.toolchains.length) notes.push('installs ' + r.toolchains.map((t) => t.label).join(', '));
        lines.push('  ' + r.id.padEnd(width) + '  ' + r.title + ': ' + r.summary);
        lines.push('  ' + ' '.repeat(width) + '  (' + notes.join(', ') + ')');
    }
    return lines.join('\n');
}

/**
 * The terminal lines for one progress event of `kit add`: one line per
 * finished step, and on a failure the output that led to it.
 * @param {any} event
 * @returns {string[]}
 */
function formatEvent(event) {
    if (!event || event.done || !event.step) return [];
    const s = event.step;
    if (s.state === 'ok') return ['[ok]   ' + s.title];
    if (s.state !== 'fail') return [];
    const lines = ['[fail] ' + s.title];
    const tail = Array.isArray(event.tail) && event.tail.length ? event.tail : (event.line ? [event.line] : []);
    for (const l of tail) lines.push('       ' + l);
    return lines;
}

/**
 * What `kit add` prints at the end.
 * @param {{ok: boolean, projectPath: string, message: string, run: string | null}} result
 */
function formatResult(result) {
    if (!result.ok) return [result.message];
    const where = tilde(result.projectPath);
    const lines = ['Ready: ' + where];
    if (result.run) lines.push('Run it: cd ' + shellQuote(where).replace(/^'~/, "~'") + ' && ' + result.run);
    lines.push('Or open the folder in Diligent and tap Run.');
    return lines;
}

/**
 * `kit doctor` output.
 * @param {{
 *   tools: Array<{name: string, version: string | null}>,
 *   packs: string[],
 *   storage: {freeMB: number, totalMB: number} | null,
 *   health: {checks: Array<{id: string, title: string, state: string, detail?: string}>} | null,
 * }} info
 */
function formatDoctor(info) {
    const lines = ['Tools'];
    for (const t of info.tools) {
        lines.push(t.version ? '  [ok]   ' + t.name + ' ' + t.version : '  [fail] ' + t.name + ' was not found');
    }
    lines.push('Toolchain packs');
    if (info.packs.length === 0) lines.push('  none installed (kit add java, c-cpp or ruby installs one)');
    for (const p of info.packs) lines.push('  ' + (/** @type {any} */ (PACK_LABELS)[p] || p));
    lines.push('Storage');
    lines.push(info.storage
        ? '  ' + Math.floor(info.storage.freeMB) + ' MB free of ' + Math.floor(info.storage.totalMB) + ' MB'
        : '  could not be read');
    lines.push('Health');
    if (!info.health) {
        lines.push('  health checks unavailable');
    } else {
        for (const c of info.health.checks) {
            const mark = c.state === 'pass' ? '[ok]  ' : c.state === 'fail' ? '[fail]' : '[?]   ';
            lines.push('  ' + mark + ' ' + c.title + (c.detail ? ': ' + c.detail : ''));
        }
    }
    return lines.join('\n');
}

/**
 * Sends one request and calls `onEvent` for each event line; resolves with
 * the final message.
 * @param {string} op
 * @param {Record<string, unknown>} [args]
 * @param {(event: any, conn: net.Socket) => void} [onEvent]
 * @param {string} [socketPath]
 * @returns {Promise<{ok: boolean, result?: any, error?: string}>}
 */
function request(op, args = {}, onEvent, socketPath = SOCKET) {
    return new Promise((resolve, reject) => {
        const conn = net.connect(socketPath);
        let rest = '';
        let settled = false;
        conn.setEncoding('utf8');
        conn.once('connect', () => conn.write(JSON.stringify({ id: 1, op, args }) + '\n'));
        conn.on('data', (chunk) => {
            const parts = (rest + chunk).split('\n');
            rest = parts.pop() || '';
            for (const line of parts) {
                if (!line.trim()) continue;
                let msg;
                try { msg = JSON.parse(line); } catch { continue; }
                if (msg.id !== 1) continue;
                if (msg.event !== undefined) {
                    if (onEvent) onEvent(msg.event, conn);
                    continue;
                }
                settled = true;
                conn.end();
                resolve(msg);
            }
        });
        conn.on('error', (err) => { if (!settled) { settled = true; reject(err); } });
        conn.on('close', () => { if (!settled) { settled = true; reject(new Error('The kit engine closed the connection.')); } });
    });
}

/** @param {string} command */
function version(command) {
    try {
        const out = execFileSync('bash', ['-c', command], { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'], timeout: 15_000 });
        const line = out.split('\n').find((l) => l.trim());
        return line ? line.trim() : null;
    } catch {
        return null;
    }
}

function installedPacks() {
    try {
        const state = JSON.parse(fs.readFileSync(path.join(HOME, '.vscodroid', 'toolchains.json'), 'utf8'));
        return (Array.isArray(state) ? state : []).map((e) => 'toolchain_' + e.name).filter((n) => n !== 'toolchain_undefined');
    } catch {
        return [];
    }
}

function storage() {
    try {
        const s = fs.statfsSync(HOME);
        return { freeMB: (s.bavail * s.bsize) / 1048576, totalMB: (s.blocks * s.bsize) / 1048576 };
    } catch {
        return null;
    }
}

/** @param {string} msg */
function fail(msg) {
    process.stderr.write(msg + '\n');
    return 1;
}

/** @param {string[]} argv @returns {Promise<number>} */
async function main(argv) {
    const [cmd, ...rest] = argv;
    if (!cmd || cmd === 'help' || cmd === '--help' || cmd === '-h') {
        console.log(HELP);
        return 0;
    }
    if (cmd === 'list') {
        let reply;
        try { reply = await request('list'); } catch { return fail(NO_ENGINE); }
        if (!reply.ok) return fail(String(reply.error));
        console.log(formatList(reply.result));
        return 0;
    }
    if (cmd === 'add' || cmd === 'prepare') {
        const prepare = cmd === 'prepare';
        if (!rest[0]) {
            return fail(prepare ? 'Usage: kit prepare <recipe>. Run "kit list" to see the recipes.'
                : 'Usage: kit add <recipe> [name]. Run "kit list" to see the recipes.');
        }
        if (prepare && rest.length > 1) return fail('Usage: kit prepare <recipe>. It takes no project name.');
        if (rest.length > 2) return fail('A project name with spaces needs quotes: kit add ' + rest[0] + ' "my app"');
        /** @type {string | null} */
        let runId = null;
        /** @type {net.Socket | null} */
        let connection = null;
        const onSigint = () => {
            if (connection && runId) {
                connection.write(JSON.stringify({ id: 2, op: 'cancel', args: { runId } }) + '\n');
                console.log('\nCancelling...');
            } else {
                process.exit(130);
            }
        };
        process.on('SIGINT', onSigint);
        let reply;
        try {
            reply = await request(cmd, prepare ? { recipe: rest[0] } : { recipe: rest[0], name: rest[1] || null }, (event, conn) => {
                runId = event.runId;
                connection = conn;
                for (const line of formatEvent(event)) console.log(line);
            });
        } catch {
            return fail(NO_ENGINE);
        } finally {
            process.off('SIGINT', onSigint);
        }
        if (!reply.ok) return fail(String(reply.error));
        const lines = prepare ? [reply.result.message] : formatResult(reply.result);
        if (reply.result.ok) {
            console.log(lines.join('\n'));
            return 0;
        }
        return fail(lines.join('\n'));
    }
    if (cmd === 'remove') {
        if (!rest[0]) return fail('Usage: kit remove <pack>. Packs: ruby, java, cpp.');
        let reply;
        try { reply = await request('remove', { pack: rest[0] }); } catch { return fail(NO_ENGINE); }
        if (!reply.ok) return fail(String(reply.error));
        console.log(reply.result);
        return 0;
    }
    if (cmd === 'doctor') {
        /** @type {any} */
        let health = null;
        try {
            const reply = await request('health');
            if (reply.ok) health = reply.result;
        } catch {
            // The engine is not running: everything else is still checked.
        }
        console.log(formatDoctor({
            tools: [
                { name: 'node', version: version('node --version') },
                { name: 'npm', version: version('npm --version') },
                { name: 'git', version: version('git --version') },
                { name: 'python3', version: version('python3 --version 2>&1') },
            ],
            packs: installedPacks(),
            storage: storage(),
            health,
        }));
        return 0;
    }
    return fail('Unknown command "' + cmd + '".\n\n' + HELP);
}

if (require.main === module) {
    main(process.argv.slice(2)).then((code) => { process.exitCode = code; });
}

module.exports = { HELP, NO_ENGINE, formatList, formatEvent, formatResult, formatDoctor, request, main, shellQuote, tilde };
