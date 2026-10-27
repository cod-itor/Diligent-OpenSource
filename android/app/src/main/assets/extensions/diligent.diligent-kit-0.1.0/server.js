// @ts-check
'use strict';

/**
 * The kit socket: `~/.diligent/kit.sock`, newline-delimited JSON.
 *
 * A request is one line `{"id": <any>, "op": "<op>", "args": {...}}`. Every
 * request ends with one line `{"id", "ok": true, "result"}` or
 * `{"id", "ok": false, "error": "<sentence>"}`. `add` sends
 * `{"id", "event": <progress>}` lines before its end, the same progress the
 * app receives through `diligentKitProgress`.
 *
 * Ops: `ping`, `list`, `add {recipe, name?}`, `cancel {runId}`,
 * `remove {pack}`, `health`.
 *
 * The socket is created with umask 077 and then chmod 600, so only this
 * app's user can connect; a socket file left by a dead editor server is
 * removed, a live one is left alone.
 */

const fs = require('fs');
const net = require('net');
const path = require('path');

/** A line longer than this is not a request, and the connection is closed. */
const MAX_LINE = 1024 * 1024;

/** @param {unknown} msg */
function encode(msg) {
    return JSON.stringify(msg) + '\n';
}

/** Splits a byte stream into JSON messages, one per line. */
class LineDecoder {
    constructor() {
        this.rest = '';
    }

    /**
     * @param {string} chunk
     * @returns {Array<{ value: any } | { error: string }>}
     */
    feed(chunk) {
        this.rest += chunk;
        if (this.rest.length > MAX_LINE && !this.rest.includes('\n')) {
            this.rest = '';
            return [{ error: 'line too long' }];
        }
        const lines = this.rest.split('\n');
        this.rest = lines.pop() || '';
        /** @type {Array<{ value: any } | { error: string }>} */
        const out = [];
        for (const line of lines) {
            if (!line.trim()) continue;
            try {
                out.push({ value: JSON.parse(line) });
            } catch {
                out.push({ error: 'not JSON' });
            }
        }
        return out;
    }
}

/**
 * @typedef {{
 *   list: () => unknown,
 *   add: (args: any, onEvent: (e: unknown) => void) => Promise<unknown>,
 *   cancel: (args: any) => unknown,
 *   remove: (args: any) => Promise<unknown>,
 *   health: () => Promise<unknown>,
 * }} Handlers
 */

/**
 * Answers one request. `send` writes one message to the client.
 * @param {any} req
 * @param {Handlers} handlers
 * @param {(msg: unknown) => void} send
 */
async function handle(req, handlers, send) {
    const id = req && typeof req === 'object' ? req.id : null;
    const op = req && typeof req === 'object' ? req.op : null;
    const args = req && typeof req.args === 'object' && req.args ? req.args : {};
    try {
        let result;
        if (op === 'ping') result = 'pong';
        else if (op === 'list') result = await handlers.list();
        else if (op === 'add') result = await handlers.add(args, (event) => send({ id, event }));
        else if (op === 'cancel') result = await handlers.cancel(args);
        else if (op === 'remove') result = await handlers.remove(args);
        else if (op === 'health') result = await handlers.health();
        else throw new Error('Unknown request: ' + String(op));
        send({ id, ok: true, result });
    } catch (err) {
        send({ id, ok: false, error: err instanceof Error ? err.message : String(err) });
    }
}

/** @param {Handlers} handlers */
function createServer(handlers) {
    return net.createServer((socket) => {
        socket.setEncoding('utf8');
        const decoder = new LineDecoder();
        const send = (/** @type {unknown} */ msg) => {
            if (!socket.destroyed) socket.write(encode(msg));
        };
        socket.on('data', (chunk) => {
            for (const item of decoder.feed(String(chunk))) {
                if ('error' in item) {
                    send({ id: null, ok: false, error: 'Bad request: ' + item.error });
                    if (item.error === 'line too long') socket.destroy();
                    continue;
                }
                handle(item.value, handlers, send);
            }
        });
        socket.on('error', () => socket.destroy());
    });
}

/** Whether something is listening at `socketPath`. @param {string} socketPath */
function socketAlive(socketPath) {
    return new Promise((resolve) => {
        const c = net.connect(socketPath);
        const done = (/** @type {boolean} */ alive) => { c.destroy(); resolve(alive); };
        c.setTimeout(1_000, () => done(false));
        c.once('connect', () => done(true));
        c.once('error', () => done(false));
    });
}

/**
 * Listens at `socketPath`, owner only. Resolves false when another live
 * engine already has the path (a second editor window), which then serves.
 * @param {net.Server} server
 * @param {string} socketPath
 * @returns {Promise<boolean>}
 */
async function listen(server, socketPath) {
    fs.mkdirSync(path.dirname(socketPath), { recursive: true, mode: 0o700 });
    if (fs.existsSync(socketPath)) {
        if (await socketAlive(socketPath)) return false;
        fs.unlinkSync(socketPath);
    }
    const oldMask = process.umask(0o077);
    try {
        await new Promise((resolve, reject) => {
            server.once('error', reject);
            server.listen(socketPath, () => {
                server.off('error', reject);
                resolve(undefined);
            });
        });
    } finally {
        process.umask(oldMask);
    }
    fs.chmodSync(socketPath, 0o600);
    return true;
}

module.exports = { encode, LineDecoder, handle, createServer, listen, socketAlive, MAX_LINE };
