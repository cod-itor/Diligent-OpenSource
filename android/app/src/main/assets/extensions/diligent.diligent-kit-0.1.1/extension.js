// @ts-check
'use strict';

/**
 * Diligent Kit (Node extension host)
 *
 * Phase 3's setup center. One engine, two front ends:
 *
 * - the terminal's `kit` command (kit.js, copied to `~/.diligent/kit/kit.js`
 *   on every activation, and run by the `kit()` function in `.bashrc`),
 *   which talks to the engine over the Unix socket `~/.diligent/kit.sock`;
 * - the app's Setup screen, which runs `diligent.kit.add(recipeId, name)`,
 *   `diligent.kit.cancel(runId)` and `diligent.kit.list()` through the
 *   bridge, and hears progress through `diligentKitProgress`.
 *
 * Recipes are `recipes/<id>.json`; engine.js says what each step does.
 */

const vscode = require('vscode');
const fs = require('fs');
const path = require('path');
const engine = require('./engine');
const { createService } = require('./service');
const server = require('./server');

/** @param {string} source @param {string} target */
function shipCli(source, target) {
    const text = fs.readFileSync(source);
    try {
        if (fs.readFileSync(target).equals(text)) return;
    } catch {
        // Not there yet.
    }
    fs.mkdirSync(path.dirname(target), { recursive: true });
    const tmp = target + '.tmp-' + process.pid;
    fs.writeFileSync(tmp, text, { mode: 0o600 });
    fs.renameSync(tmp, target);
}

/** @param {import('vscode').ExtensionContext} context */
function activate(context) {
    const { home, projectsDir } = engine.locations();
    const log = (/** @type {string} */ msg) => console.warn('[Diligent kit] ' + msg);
    const service = createService({
        extensionDir: context.extensionPath, home, projectsDir,
        executeCommand: vscode.commands.executeCommand, log,
    });

    try {
        shipCli(path.join(context.extensionPath, 'kit.js'), path.join(home, '.diligent', 'kit', 'kit.js'));
    } catch (err) {
        log('could not install the kit command: ' + String(err));
    }

    const socketPath = path.join(home, '.diligent', 'kit.sock');
    const srv = server.createServer(service);
    let owned = false;
    server.listen(srv, socketPath).then((ok) => {
        owned = ok;
        if (!ok) log('another editor window serves ' + socketPath);
    }, (err) => log('could not listen on ' + socketPath + ': ' + String(err)));

    context.subscriptions.push(
        {
            dispose: () => {
                srv.close();
                if (owned) {
                    try { fs.unlinkSync(socketPath); } catch { /* already gone */ }
                }
            },
        },
        // A third argument "prepare" is `kit prepare` (the Setup screen's
        // Prepare for offline), so the app reaches it through the command it
        // is already allowed to send.
        vscode.commands.registerCommand('diligent.kit.add',
            (/** @type {string} */ recipeId, /** @type {string} */ name, /** @type {string} */ mode) => service.start(recipeId, name, mode)),
        vscode.commands.registerCommand('diligent.kit.cancel', (/** @type {string} */ runId) => service.cancel({ runId })),
        vscode.commands.registerCommand('diligent.kit.list', () => JSON.stringify(service.list())),
    );
}

function deactivate() {}

module.exports = { activate, deactivate, shipCli };
