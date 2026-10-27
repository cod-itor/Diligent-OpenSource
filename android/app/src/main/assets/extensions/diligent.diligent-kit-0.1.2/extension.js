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
 *
 * "Explain this error" (`diligent.kit.explain`, the editor's context menu
 * when text is selected) applies errors.json to the selection and offers the
 * fixes the editor can carry out; the app's screens (Health, Storage) are
 * offered on the Run screen instead.
 */

const vscode = require('vscode');
const fs = require('fs');
const path = require('path');
const engine = require('./engine');
const { createService } = require('./service');
const server = require('./server');
const errors = require('./errors');
const { execFile } = require('child_process');

/** The fixes this editor carries out itself. */
const EDITOR_FIXES = new Set(['openFileAtLine', 'runRecipe', 'gitConfig', 'stopTask', 'openProject']);

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
        for (const file of ['kit.js', 'errors.js', 'errors.json']) {
            shipCli(path.join(context.extensionPath, file), path.join(home, '.diligent', 'kit', file));
        }
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
        vscode.commands.registerCommand('diligent.kit.explain', () => explainSelection(log)),
    );
}

/** "Explain this error": the selected text, explained, with a fix button where one applies. */
async function explainSelection(/** @type {(msg: string) => void} */ log) {
    const editor = vscode.window.activeTextEditor;
    const text = editor ? editor.selections.map((s) => editor.document.getText(s)).join('\n') : '';
    if (!text.trim()) {
        vscode.window.showInformationMessage('Select the error text first, then choose Explain this error.');
        return;
    }
    const found = errors.explain(text);
    if (found.length === 0) {
        vscode.window.showInformationMessage('No known error found in the selected text.');
        return;
    }
    const first = found[0];
    const fixes = first.fixes.filter((f) => EDITOR_FIXES.has(f.action));
    const more = found.length > 1 ? ' Also: ' + found.slice(1).map((f) => f.title).join('; ') + '.' : '';
    const picked = await vscode.window.showInformationMessage(first.title + '. ' + first.explanation + more, ...fixes.map((f) => f.label));
    const fix = fixes.find((f) => f.label === picked);
    if (!fix) return;
    try {
        await runFix(fix, editor ? editor.document.uri : undefined);
    } catch (err) {
        log('fix ' + fix.action + ' failed: ' + String(err));
        vscode.window.showWarningMessage('That did not work: ' + (err instanceof Error ? err.message : String(err)));
    }
}

/**
 * Carries out one fix in the editor.
 * @param {{ action: string, recipe?: string, file?: string, line?: number }} fix
 * @param {import('vscode').Uri} [from] the document the error was selected in
 */
async function runFix(fix, from) {
    if (fix.action === 'openFileAtLine' && fix.file) {
        const folder = (from && vscode.workspace.getWorkspaceFolder(from)) || (vscode.workspace.workspaceFolders || [])[0];
        const file = path.isAbsolute(fix.file) || !folder ? fix.file : path.join(folder.uri.fsPath, fix.file);
        const line = Math.max(0, (fix.line || 1) - 1);
        const position = new vscode.Position(line, 0);
        await vscode.window.showTextDocument(vscode.Uri.file(file), { selection: new vscode.Range(position, position) });
    } else if (fix.action === 'runRecipe' && fix.recipe) {
        const terminal = vscode.window.createTerminal('kit');
        terminal.show();
        terminal.sendText('kit add ' + fix.recipe);
    } else if (fix.action === 'stopTask') {
        await vscode.commands.executeCommand('workbench.action.tasks.terminate');
    } else if (fix.action === 'openProject') {
        await vscode.commands.executeCommand('workbench.action.files.openFolder');
    } else if (fix.action === 'gitConfig') {
        const name = await vscode.window.showInputBox({ prompt: 'Your name, for Git' });
        if (!name) return;
        const email = await vscode.window.showInputBox({ prompt: 'Your email, for Git' });
        if (!email) return;
        for (const [key, value] of [['user.name', name], ['user.email', email]]) {
            await new Promise((resolve, reject) => execFile('git', ['config', '--global', key, value],
                (err) => (err ? reject(err) : resolve(undefined))));
        }
        vscode.window.showInformationMessage('Git now knows your name and email.');
    }
}

function deactivate() {}

module.exports = { activate, deactivate, shipCli };
