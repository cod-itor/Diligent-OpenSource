/**
 * Self-check for Diligent's bridge extension, both directions.
 *
 * Kotlin to editor: a message on BroadcastChannel('diligent-events') runs an
 * editor command only when the command is on the extension's allowlist. Any web
 * extension in the page can post on that channel, so the refusal is the part
 * that matters, and it is checked first. A `{"$path"}` argument becomes a URI on
 * the editor server, the same scheme and authority as the extension's own.
 *
 * Editor to Kotlin: `diligentXxx` calls travel through VSCodroid's relay, which
 * is extracted from MainActivity.kt and run here against a stubbed AndroidBridge,
 * so the one Diligent branch in it is exercised as the WebView runs it. The
 * ready handshake (`diligentEditorReady`) is driven over that real relay,
 * including the case where the relay is installed after the extension starts
 * asking, which is the order a cold start produces.
 *
 *   node scripts/test-diligent-bridge.js
 */

const assert = require('assert');
const fs = require('fs');
const path = require('path');
const vm = require('vm');
const Module = require('module');
const { newestExtensionDir } = require('./lib/bundled-extension');

const ROOT = path.join(__dirname, '..');
const MAIN_ACTIVITY = path.join(ROOT, 'android/app/src/main/kotlin/com/vscodroid/MainActivity.kt');
const EDITOR_CHANNEL = path.join(ROOT, 'android/app/src/main/kotlin/com/vscodroid/diligent/EditorChannel.kt');
const EXTENSION = path.join(newestExtensionDir('diligent.diligent-bridge-'), 'extension.js');

// Kotlin's escape for a literal dollar inside a raw string.
const DOLLAR_ESCAPE = "${'$'}";

// ---- the workbench, stubbed ----------------------------------------------

const executed = [];
const registered = new Map();
// User-level settings as the workbench keeps them: a key absent here is unset.
const userSettings = new Map();
const statusItems = [];
const vscodeStub = {
    ConfigurationTarget: { Global: 1, Workspace: 2 },
    StatusBarAlignment: { Left: 1, Right: 2 },
    l10n: { t: (s) => s },
    window: {
        createStatusBarItem: (id, alignment, priority) => {
            const item = { id, alignment, priority, shown: false, show() { this.shown = true; }, hide() { this.shown = false; }, dispose() {} };
            statusItems.push(item);
            return item;
        },
    },
    workspace: {
        getConfiguration: () => ({
            inspect: (key) => ({ key, globalValue: userSettings.get(key) }),
            update: async (key, value, target) => {
                assert.strictEqual(target, 1, `${key} was written somewhere other than user settings`);
                if (value === undefined) userSettings.delete(key); else userSettings.set(key, value);
            },
        }),
    },
    commands: {
        executeCommand: (cmd, ...args) => { executed.push({ cmd, args }); return Promise.resolve(); },
        registerCommand: (id, fn) => { registered.set(id, fn); return { dispose() {} }; },
    },
};
const resolveFilename = Module._resolveFilename;
Module._resolveFilename = function (request, ...rest) {
    return request === 'vscode' ? 'vscode' : resolveFilename.call(this, request, ...rest);
};
require.cache.vscode = { id: 'vscode', filename: 'vscode', loaded: true, exports: vscodeStub };

const bridge = require(EXTENSION);

/** A URI as the workbench has one: `with` replaces the named parts. */
function uri(parts) {
    return {
        ...parts,
        with(change) { return uri({ ...this, ...change, with: undefined }); },
    };
}
const BASE = uri({
    scheme: 'vscode-remote',
    authority: '127.0.0.1:13337',
    path: '/data/user/0/com.ditarector.diligent/files/extensions/diligent.diligent-bridge-0.1.0',
    query: 'q',
    fragment: 'f',
});

const settle = () => new Promise((resolve) => setTimeout(resolve, 20));

// ---- the relay, as MainActivity injects it -------------------------------

/** The injected relay script, with Kotlin's escapes resolved. */
function extractRelay() {
    const lines = fs.readFileSync(MAIN_ACTIVITY, 'utf8').split('\n');
    const fn = lines.findIndex((l) => l.includes('private fun injectBridgeRelay()'));
    assert.notStrictEqual(fn, -1, 'injectBridgeRelay() is gone from MainActivity.kt; point this at its new home');
    const open = lines.findIndex((l, i) => i > fn && l.trim() === '"""');
    const close = lines.findIndex((l, i) => i > open && l.trim().startsWith('"""'));
    assert.ok(open !== -1 && close > open + 1, 'could not find the raw string in injectBridgeRelay()');
    const js = lines.slice(open + 1, close).join('\n');
    assert.ok(js.includes('diligent[A-Z]'), 'the relay has no Diligent branch');
    return js.split(DOLLAR_ESCAPE).join('$');
}

/** Installs the relay on its own channel, beside a stubbed AndroidBridge. */
function installRelay(androidBridge) {
    vm.runInNewContext(extractRelay(), {
        AndroidBridge: androidBridge,
        BroadcastChannel,
        window: { __vscodroid: { authToken: 'test-token' } },
        console,
    });
}

async function main() {
    // ---- the allowlist ---------------------------------------------------

    for (const cmd of ['workbench.action.terminal.sendSequence', 'workbench.extensions.installExtension', '']) {
        const outcome = bridge.handleEvent({ cmd, args: [] }, BASE, vscodeStub.commands.executeCommand);
        assert.deepStrictEqual(outcome, { refused: cmd }, `${JSON.stringify(cmd)} is not on the allowlist and ran`);
    }
    for (const junk of [null, 'vscode.open', { args: [] }, { cmd: 3 }]) {
        assert.deepStrictEqual(
            bridge.handleEvent(junk, BASE, vscodeStub.commands.executeCommand),
            { refused: 'not a message' },
        );
    }
    assert.strictEqual(executed.length, 0, 'a refused message reached the workbench');

    // ---- an allowed command, with a path ----------------------------------

    const outcome = bridge.handleEvent(
        { cmd: 'vscode.open', args: [{ $path: '/storage/emulated/0/proj/a b.js' }, 2] },
        BASE, vscodeStub.commands.executeCommand,
    );
    assert.deepStrictEqual(outcome, { ran: 'vscode.open' });
    const [{ cmd, args }] = executed;
    assert.strictEqual(cmd, 'vscode.open');
    assert.strictEqual(args[0].scheme, 'vscode-remote');
    assert.strictEqual(args[0].authority, '127.0.0.1:13337');
    assert.strictEqual(args[0].path, '/storage/emulated/0/proj/a b.js');
    // The extension's own query and fragment are not the file's.
    assert.strictEqual(args[0].query, '');
    assert.strictEqual(args[0].fragment, '');
    assert.strictEqual(args[1], 2, 'a plain argument was changed on the way');

    // A message without args runs with none, rather than throwing.
    executed.length = 0;
    bridge.handleEvent({ cmd: 'vscode.open' }, BASE, vscodeStub.commands.executeCommand);
    assert.deepStrictEqual(executed, [{ cmd: 'vscode.open', args: [] }]);

    // ---- the channel name is the one Kotlin posts on ----------------------

    const channelName = /const val CHANNEL = "([^"]+)"/.exec(fs.readFileSync(EDITOR_CHANNEL, 'utf8'));
    assert.ok(channelName, 'EditorChannel.CHANNEL is gone; point this at the channel name');
    assert.ok(
        fs.readFileSync(EXTENSION, 'utf8').includes(`'${channelName[1]}'`),
        `the extension does not listen on '${channelName[1]}', the channel EditorChannel posts on`,
    );

    // ---- the editor layout of each view ------------------------------------
    //
    // Mobile view saves the user's own values once, sets its own, and clears
    // the side areas; Desktop view puts the user's values back exactly,
    // including "never set". A second Mobile apply (every page load) must not
    // save Mobile view's values over the user's.

    const state = new Map();
    const globalState = { get: (k) => state.get(k), update: async (k, v) => { state.set(k, v); } };
    userSettings.set('editor.fontSize', 18);
    userSettings.set('workbench.statusBar.visible', true);
    executed.length = 0;

    assert.strictEqual(await bridge.applyView('mobile', globalState, vscodeStub), true);
    for (const [key, value] of Object.entries(bridge.MOBILE_SETTINGS)) {
        assert.deepStrictEqual(userSettings.get(key), value, `Mobile view did not set ${key}`);
    }
    assert.deepStrictEqual(
        executed.map((e) => e.cmd),
        ['workbench.action.closeSidebar', 'workbench.action.closePanel', 'workbench.action.closeAuxiliaryBar'],
    );

    await bridge.applyView('mobile', globalState, vscodeStub);
    await bridge.applyView('desktop', globalState, vscodeStub);
    assert.strictEqual(userSettings.get('editor.fontSize'), 18, 'the user\'s font size was not restored');
    assert.strictEqual(userSettings.get('workbench.statusBar.visible'), true);
    for (const key of Object.keys(bridge.MOBILE_SETTINGS)) {
        if (key === 'editor.fontSize' || key === 'workbench.statusBar.visible') continue;
        assert.ok(!userSettings.has(key), `${key} was never set by the user and is left set after Desktop view`);
    }
    // Desktop again, from Desktop: nothing to restore, nothing touched.
    userSettings.set('editor.fontSize', 20);
    await bridge.applyView('desktop', globalState, vscodeStub);
    assert.strictEqual(userSettings.get('editor.fontSize'), 20, 'Desktop view rewrote a setting it never changed');
    assert.strictEqual(await bridge.applyView('tablet', globalState, vscodeStub), false);

    // The Terminal tab: close the panel, then open the terminal (maximized by the setting).
    executed.length = 0;
    await bridge.showTerminal(vscodeStub);
    assert.deepStrictEqual(executed.map((e) => e.cmd), ['workbench.action.closePanel', 'workbench.action.terminal.focus']);
    executed.length = 0;
    await bridge.showEditor(vscodeStub);
    assert.deepStrictEqual(executed.map((e) => e.cmd), ['workbench.action.closePanel', 'workbench.action.focusActiveEditorGroup']);
    assert.strictEqual(bridge.MOBILE_SETTINGS['workbench.panel.opensMaximized'], 'always');

    // ---- switching back from Desktop view ------------------------------------

    const asked = [];
    await bridge.switchToMobileView(async (method, args) => { asked.push([method, ...args]); });
    assert.deepStrictEqual(asked, [['diligentSetView', 'mobile']]);
    const item = { shown: false, show() { this.shown = true; }, hide() { this.shown = false; } };
    bridge.updateViewItem(item, 'desktop');
    assert.ok(item.shown, 'Desktop view does not offer Mobile view in the status bar');
    bridge.updateViewItem(item, 'mobile');
    assert.ok(!item.shown, 'Mobile view offers itself');
    bridge.updateViewItem(item, undefined);
    assert.ok(item.shown, 'before any view is applied the editor is in its desktop layout and should offer Mobile view');

    // ---- names ------------------------------------------------------------

    assert.strictEqual(bridge.bridgeMethodName('getView'), 'diligentGetView');
    assert.strictEqual(bridge.bridgeMethodName('editorReady'), 'diligentEditorReady');

    // ---- a call nobody answers fails, it does not hang --------------------

    await assert.rejects(bridge.callBridge('diligentEditorReady', [], 50), /no answer from the app/);

    // ---- through the real relay -------------------------------------------

    const calls = [];
    const androidBridge = {
        diligentEditorReady: (...a) => { calls.push(['diligentEditorReady', ...a]); },
        diligentEcho: (token, x, y) => { calls.push(['diligentEcho', token, x, y]); return `${x}+${y}`; },
        // Not a Diligent method: the branch must not reach it by name.
        reclaimSafMirror: () => { throw new Error('reached a VSCodroid method through the Diligent branch'); },
    };

    // The extension starts asking before the relay exists, as on a cold start.
    const ready = bridge.announceReady();
    await new Promise((resolve) => setTimeout(resolve, 1200));
    assert.strictEqual(calls.length, 0);
    installRelay(androidBridge);
    assert.strictEqual(await ready, true, 'the extension gave up before the relay was installed');
    assert.deepStrictEqual(calls[0], ['diligentEditorReady', 'test-token']);

    // Arguments arrive after the token, in order, and the answer comes back.
    assert.strictEqual(await bridge.callBridge('diligentEcho', ['a', 'b'], 1000), 'a+b');
    assert.deepStrictEqual(calls.at(-1), ['diligentEcho', 'test-token', 'a', 'b']);

    // A Diligent name with no method is not answered as a success.
    await assert.rejects(bridge.callBridge('diligentMissing', [], 1000));

    // ---- activate wires the channel and the commands -----------------------

    calls.length = 0;
    executed.length = 0;
    const subscriptions = [];
    bridge.activate({ subscriptions, extensionUri: BASE, globalState });
    await settle();
    const poster = new BroadcastChannel('diligent-events');
    poster.postMessage({ cmd: 'workbench.action.quit', args: [] });
    poster.postMessage({ cmd: 'vscode.open', args: [{ $path: '/p/x.py' }] });
    await settle();
    poster.close();
    assert.deepStrictEqual(executed.map((e) => e.cmd), ['vscode.open'], 'activate ran a refused command');
    assert.strictEqual(executed[0].args[0].path, '/p/x.py');
    // The status bar item has an id (one shared table across extension hosts)
    // and runs the contributed command.
    assert.strictEqual(statusItems.length, 1);
    assert.strictEqual(statusItems[0].id, 'diligent.mobileView');
    assert.ok(registered.has('diligent.switchToMobileView'), 'the contributed command is not registered');
    const manifest = JSON.parse(fs.readFileSync(path.join(path.dirname(EXTENSION), 'package.json'), 'utf8'));
    assert.deepStrictEqual(manifest.contributes.commands.map((c) => c.command), ['diligent.switchToMobileView']);

    for (const id of ['diligent.view.apply', 'diligent.mobile.showEditor', 'diligent.mobile.showTerminal']) {
        assert.ok(registered.has(id), `${id} is allowed but was not registered`);
        assert.ok(bridge.ALLOWED_COMMANDS.has(id), `${id} is registered but Kotlin may not send it`);
    }
    // Every command Kotlin sends is allowed. Read from the Kotlin side, so a
    // new command there without its allowlist entry fails here, not silently
    // on the phone.
    const kotlinSent = new Set();
    const diligentDir = path.join(ROOT, 'android/app/src/main/kotlin/com/vscodroid/diligent');
    for (const file of fs.readdirSync(diligentDir, { recursive: true })) {
        if (!String(file).endsWith('.kt')) continue;
        const text = fs.readFileSync(path.join(diligentDir, String(file)), 'utf8');
        for (const m of text.matchAll(/(?:editorCommand|editor\.run)\("([\w.]+)"/g)) kotlinSent.add(m[1]);
        for (const m of text.matchAll(/const val COMMAND_\w+ = "([\w.]+)"/g)) kotlinSent.add(m[1]);
    }
    assert.ok(kotlinSent.size > 0, 'no editor commands were read from the Kotlin side');
    const unallowed = [...kotlinSent].filter((c) => !bridge.ALLOWED_COMMANDS.has(c));
    assert.deepStrictEqual(unallowed, [], `Kotlin sends commands the extension refuses: ${unallowed.join(', ')}`);

    for (const name of bridge.BRIDGE_METHODS) {
        assert.ok(registered.has('diligent.bridge.' + name), `diligent.bridge.${name} was not registered`);
    }
    await new Promise((resolve) => setTimeout(resolve, 100));
    assert.ok(calls.some((c) => c[0] === 'diligentEditorReady'), 'activate did not announce itself');
    subscriptions.forEach((s) => s.dispose());

    console.log(`diligent bridge: allowlist (${kotlinSent.size} Kotlin commands), paths, view layouts, relay branch and ready handshake OK`);
    // The relay's channel stays open, as it does in the page.
    process.exit(0);
}

main().catch((err) => {
    console.error(err);
    process.exit(1);
});
