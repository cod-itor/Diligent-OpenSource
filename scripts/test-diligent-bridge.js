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
// What activate subscribed to, so a test can fire the editor's events.
const listeners = { activeEditor: [], documentChange: [], documentSave: [] };
const vscodeStub = {
    ConfigurationTarget: { Global: 1, Workspace: 2 },
    StatusBarAlignment: { Left: 1, Right: 2 },
    l10n: { t: (s) => s },
    window: {
        activeTextEditor: undefined,
        onDidChangeActiveTextEditor: (fn) => { listeners.activeEditor.push(fn); return { dispose() {} }; },
        createStatusBarItem: (id, alignment, priority) => {
            const item = { id, alignment, priority, shown: false, show() { this.shown = true; }, hide() { this.shown = false; }, dispose() {} };
            statusItems.push(item);
            return item;
        },
    },
    workspace: {
        onDidChangeTextDocument: (fn) => { listeners.documentChange.push(fn); return { dispose() {} }; },
        onDidSaveTextDocument: (fn) => { listeners.documentSave.push(fn); return { dispose() {} }; },
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
    await settle();
    assert.strictEqual(executed.length, 0, 'a refused message reached the workbench');

    // ---- an allowed command, with a path ----------------------------------

    const outcome = bridge.handleEvent(
        { cmd: 'vscode.open', args: [{ $path: '/storage/emulated/0/proj/a b.js' }, 2] },
        BASE, vscodeStub.commands.executeCommand,
    );
    assert.deepStrictEqual(outcome, { ran: 'vscode.open' });
    await settle();
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
    await settle();
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

    // A change made while in Mobile view survives the next page load's apply.
    userSettings.set('editor.fontSize', 17);
    await bridge.applyView('mobile', globalState, vscodeStub);
    assert.strictEqual(userSettings.get('editor.fontSize'), 17, 'a page load undid the user\'s change in Mobile view');
    // A key added by a newer version is saved and set on the next apply.
    const { 'window.autoDetectColorScheme': _dropped, ...older } = state.get('diligent.savedDesktopSettings');
    state.set('diligent.savedDesktopSettings', older);
    userSettings.set('window.autoDetectColorScheme', false);
    await bridge.applyView('mobile', globalState, vscodeStub);
    assert.strictEqual(userSettings.get('window.autoDetectColorScheme'), true, 'a newly listed key was not applied');
    await bridge.applyView('desktop', globalState, vscodeStub);
    assert.strictEqual(userSettings.get('window.autoDetectColorScheme'), false, 'the newly listed key was not restored');
    assert.strictEqual(userSettings.get('editor.fontSize'), 18, 'the user\'s font size was not restored');
    assert.strictEqual(userSettings.get('workbench.statusBar.visible'), true);
    for (const key of Object.keys(bridge.MOBILE_SETTINGS)) {
        // Set by the user above: these are restored, not removed.
        if (['editor.fontSize', 'workbench.statusBar.visible', 'window.autoDetectColorScheme'].includes(key)) continue;
        assert.ok(!userSettings.has(key), `${key} was never set by the user and is left set after Desktop view`);
    }
    // A page that died before it recorded the switch leaves Mobile view's own
    // values behind; they are not taken for the user's.
    userSettings.set('workbench.statusBar.visible', false);
    await bridge.applyView('mobile', globalState, vscodeStub);
    await bridge.applyView('desktop', globalState, vscodeStub);
    assert.ok(!userSettings.has('workbench.statusBar.visible'), 'Mobile view\'s own value was restored as the user\'s');

    // Mobile view on a phone: nothing above the code, the line numbers kept,
    // Diligent's own themes.
    for (const [key, value] of Object.entries({
        'workbench.editor.showTabs': 'none',
        'breadcrumbs.enabled': false,
        'workbench.editor.editorActionsLocation': 'hidden',
        'editor.minimap.enabled': false,
        'editor.stickyScroll.enabled': false,
        'editor.scrollBeyondLastLine': false,
        'editor.lineNumbers': 'on',
        'workbench.preferredDarkColorTheme': 'Diligent Mobile Dark',
        'workbench.preferredLightColorTheme': 'Diligent Mobile Light',
    })) {
        assert.deepStrictEqual(bridge.MOBILE_SETTINGS[key], value, `Mobile view's ${key}`);
    }
    // A phone already in Mobile view before the update: a key the earlier
    // version set, still at its value, takes the new one on the next page
    // load; one the user changed in Mobile view keeps the user's; and Desktop
    // view still restores the user's own value from before Mobile view.
    userSettings.set('workbench.editor.showTabs', 'multiple');
    userSettings.set('workbench.preferredDarkColorTheme', 'Monokai');
    await bridge.applyView('mobile', globalState, vscodeStub);
    userSettings.set('workbench.editor.showTabs', 'single');
    userSettings.set('workbench.preferredLightColorTheme', 'Default Light Modern');
    userSettings.set('workbench.preferredDarkColorTheme', 'Solarized Dark');
    await bridge.applyView('mobile', globalState, vscodeStub);
    assert.strictEqual(userSettings.get('workbench.editor.showTabs'), 'none', 'the earlier tab strip value was kept');
    assert.strictEqual(userSettings.get('workbench.preferredLightColorTheme'), 'Diligent Mobile Light');
    assert.strictEqual(userSettings.get('workbench.preferredDarkColorTheme'), 'Solarized Dark', 'a theme the user chose in Mobile view was replaced');
    await bridge.applyView('desktop', globalState, vscodeStub);
    assert.strictEqual(userSettings.get('workbench.editor.showTabs'), 'multiple', 'the user\'s tab strip was not restored');
    assert.strictEqual(userSettings.get('workbench.preferredDarkColorTheme'), 'Monokai', 'the user\'s dark theme was not restored');
    assert.ok(!userSettings.has('workbench.preferredLightColorTheme'));
    userSettings.delete('workbench.editor.showTabs');
    userSettings.delete('workbench.preferredDarkColorTheme');
    for (const key of Object.keys(bridge.EARLIER_MOBILE_VALUES)) {
        assert.ok(key in bridge.MOBILE_SETTINGS, `${key} has earlier values but is no longer a Mobile view key`);
    }

    // Kotlin's messages run one at a time, in order, however long each takes.
    const log = [];
    const slow = (name, ms) => () => new Promise((r) => setTimeout(() => { log.push(name); r(name); }, ms));
    await Promise.all([bridge.inOrder(slow('apply', 30)), bridge.inOrder(slow('terminal', 1)), bridge.inOrder(() => { throw new Error('x'); }).catch(() => log.push('failed')), bridge.inOrder(slow('after', 1))]);
    assert.deepStrictEqual(log, ['apply', 'terminal', 'failed', 'after'], 'queued commands overlapped or a failure stopped the queue');

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
    assert.deepStrictEqual(executed.map((e) => e.cmd), ['workbench.action.closePanel', 'workbench.action.closeSidebar', 'workbench.action.focusActiveEditorGroup']);
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
        diligentGetHealth: (token) => { calls.push(['diligentGetHealth', token]); return '{"checks":[]}'; },
        diligentInstallToolchain: (token, name) => { calls.push(['diligentInstallToolchain', token, name]); },
        diligentKitProgress: (token, json) => { calls.push(['diligentKitProgress', token, json]); },
        diligentRemoveToolchain: (token, name) => { calls.push(['diligentRemoveToolchain', token, name]); },
        diligentActiveFile: (token, json) => { calls.push(['diligentActiveFile', token, json]); },
        diligentNetworkType: (token) => { calls.push(['diligentNetworkType', token]); return 'wifi'; },
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
    // Never lose work: auto-save one second after typing stops. A contributed
    // default, in the DEFAULT layer, so the user's own choice in the Settings
    // editor still wins (FirstRunSetup.writeDefaultSettings explains why the
    // machine settings file cannot hold a preference).
    assert.deepStrictEqual(manifest.contributes.configurationDefaults, {
        'files.autoSave': 'afterDelay',
        'files.autoSaveDelay': 1000,
    });

    // The two themes Mobile view names exist, under the ids it names.
    const themeIds = (manifest.contributes.themes || []).map((t) => t.id);
    assert.deepStrictEqual(themeIds.sort(), [bridge.MOBILE_SETTINGS['workbench.preferredDarkColorTheme'],
        bridge.MOBILE_SETTINGS['workbench.preferredLightColorTheme']].sort());
    for (const theme of manifest.contributes.themes) {
        const file = JSON.parse(fs.readFileSync(path.join(path.dirname(EXTENSION), theme.path), 'utf8'));
        assert.ok(/^#[0-9A-F]{6}$/i.test(file.colors['editor.background']), `${theme.id} has no editor background`);
        assert.strictEqual(file.colors['editorGutter.background'], file.colors['editor.background'], `${theme.id}: the gutter is a seam`);
        assert.strictEqual(file.type, theme.uiTheme === 'vs' ? 'light' : 'dark');
    }
    assert.ok(bridge.ALLOWED_COMMANDS.has('workbench.action.files.saveAll'), 'sync cannot save all files');

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
    // The kit engine's calls: getHealth's answer comes back to the caller,
    // installToolchain arrives with the token first, progress is passed on.
    for (const name of ['kitProgress', 'getHealth', 'installToolchain', 'removeToolchain', 'networkType']) {
        assert.ok(bridge.BRIDGE_METHODS.includes(name), `${name} is not a bridge method`);
        assert.ok((manifest.activationEvents || []).includes('onCommand:diligent.bridge.' + name),
            `diligent.bridge.${name} does not activate the extension`);
    }
    assert.strictEqual(await registered.get('diligent.bridge.getHealth')(), '{"checks":[]}');
    await registered.get('diligent.bridge.installToolchain')('java');
    assert.deepStrictEqual(calls.at(-1), ['diligentInstallToolchain', 'test-token', 'java']);
    await registered.get('diligent.bridge.removeToolchain')('java');
    assert.deepStrictEqual(calls.at(-1), ['diligentRemoveToolchain', 'test-token', 'java']);
    // diligent-ai's Wi-Fi gate gets the answer back.
    assert.strictEqual(await registered.get('diligent.bridge.networkType')(), 'wifi');
    assert.deepStrictEqual(calls.at(-1), ['diligentNetworkType', 'test-token']);
    await registered.get('diligent.bridge.kitProgress')('{"runId":"r"}');
    assert.deepStrictEqual(calls.at(-1), ['diligentKitProgress', 'test-token', '{"runId":"r"}']);
    for (const id of ['diligent.kit.add', 'diligent.kit.cancel',
        'editor.action.inlineSuggest.commit', 'editor.action.inlineSuggest.showNext']) {
        assert.ok(bridge.ALLOWED_COMMANDS.has(id), `${id} is not allowed`);
    }
    for (const id of ['workbench.action.chat.open', 'workbench.extensions.search', 'diligent.mobile.attachSession']) {
        assert.ok(bridge.ALLOWED_COMMANDS.has(id), `${id} is not allowed`);
    }
    for (const id of ['workbench.action.terminal.sendSequence', 'workbench.action.terminal.new']) {
        assert.ok(!bridge.ALLOWED_COMMANDS.has(id), `${id} must not be allowed`);
    }
    // Attach: a diligent-ai-* session only, in a terminal of its own.
    assert.ok(registered.has('diligent.mobile.attachSession'), 'attachSession is allowed but not registered');
    const terminals = [];
    const termApi = { window: { createTerminal: (opts) => {
        const t = { opts, shown: false, sent: [], show() { this.shown = true; }, sendText(x) { this.sent.push(x); } };
        terminals.push(t);
        return t;
    } } };
    assert.strictEqual(bridge.attachSession('diligent-ai-claude-my_app2', termApi), true);
    assert.deepStrictEqual(terminals[0].opts, { name: 'diligent-ai-claude-my_app2' });
    assert.ok(terminals[0].shown);
    assert.deepStrictEqual(terminals[0].sent, ["tmux attach -t '=diligent-ai-claude-my_app2'"]);
    for (const bad of ['diligent-ai-', 'diligent-ai-x\'; rm -rf ~', 'diligent-ai-X', 'diligent-my-app-dev',
        'diligent-ai-a b', 'diligent-ai-a\n', 'x-diligent-ai-a', 42, null, undefined]) {
        assert.strictEqual(bridge.attachSession(bad, termApi), false, `${JSON.stringify(bad)} was attached`);
    }
    assert.strictEqual(terminals.length, 1, 'a refused name opened a terminal');

    // Run: a diligent-* run name only, one terminal per run, its own script.
    assert.ok(bridge.ALLOWED_COMMANDS.has('diligent.mobile.showRun'), 'showRun is not allowed');
    assert.ok(registered.has('diligent.mobile.showRun'), 'showRun is allowed but not registered');
    const runTerminals = [];
    const runApi = { window: {
        get terminals() { return runTerminals.filter((t) => !t.disposed); },
        createTerminal: (opts) => {
            const t = { opts, name: opts.name, shown: false, disposed: false, sent: [],
                show() { this.shown = true; }, sendText(x) { this.sent.push(x); }, dispose() { this.disposed = true; } };
            runTerminals.push(t);
            return t;
        },
    } };
    assert.strictEqual(bridge.showRun('diligent-my_app-dev', 'dev', runApi), true);
    assert.deepStrictEqual(runTerminals[0].opts, { name: 'Run: dev' });
    assert.ok(runTerminals[0].shown);
    assert.deepStrictEqual(runTerminals[0].sent, [' clear; bash ~/.diligent/term/diligent-my_app-dev.sh']);
    assert.strictEqual(bridge.showRun('diligent-my_app-dev', 'dev', runApi), true);
    assert.ok(runTerminals[0].disposed, 'running again left the old terminal open');
    assert.strictEqual(runApi.window.terminals.length, 1);
    assert.strictEqual(bridge.showRun('diligent-a-b', 'x\u001b[2Jy\n', runApi), true);
    assert.strictEqual(runTerminals.at(-1).name, 'Run: x[2Jy', 'control characters reached the title');
    assert.strictEqual(bridge.showRun('diligent-a-b', 42, runApi), true);
    assert.strictEqual(runTerminals.at(-1).name, 'Run: diligent-a-b');
    const before = runTerminals.length;
    for (const bad of ['diligent-', "diligent-x'; rm -rf ~", 'diligent-X', 'diligent-a b', 'diligent-a\n',
        'x-diligent-a', '../diligent-a', 'diligent-a/../../b', 42, null, undefined]) {
        assert.strictEqual(bridge.showRun(bad, 'x', runApi), false, `${JSON.stringify(bad)} was shown`);
    }
    assert.strictEqual(runTerminals.length, before, 'a refused name opened a terminal');

    await new Promise((resolve) => setTimeout(resolve, 100));
    assert.ok(calls.some((c) => c[0] === 'diligentEditorReady'), 'activate did not announce itself');

    // ---- the active file, for the native file name chip --------------------

    const doc = (scheme, p, isDirty = false) => ({ uri: { scheme, path: p }, isDirty });
    assert.deepStrictEqual(JSON.parse(bridge.activeFileJson(undefined)), { path: null, name: null, dirty: false });
    assert.deepStrictEqual(JSON.parse(bridge.activeFileJson({ document: doc('vscode-remote', '/p/src/app.js', true) })),
        { path: '/p/src/app.js', name: 'app.js', dirty: true });
    assert.deepStrictEqual(JSON.parse(bridge.activeFileJson({ document: doc('untitled', 'Untitled-1') })),
        { path: null, name: 'Untitled-1', dirty: false });
    // Ready was answered, so the file in front was sent once activate's handshake finished.
    assert.deepStrictEqual(calls.filter((c) => c[0] === 'diligentActiveFile').at(-1),
        ['diligentActiveFile', 'test-token', '{"path":null,"name":null,"dirty":false}']);
    const shownDoc = doc('vscode-remote', '/p/a.py');
    vscodeStub.window.activeTextEditor = { document: shownDoc };
    listeners.activeEditor.forEach((fn) => fn(vscodeStub.window.activeTextEditor));
    await settle();
    assert.deepStrictEqual(calls.at(-1), ['diligentActiveFile', 'test-token', '{"path":"/p/a.py","name":"a.py","dirty":false}']);
    // Typing: one message when the dot appears, not one per character.
    const callsBefore = calls.length;
    shownDoc.isDirty = true;
    for (let i = 0; i < 3; i += 1) listeners.documentChange.forEach((fn) => fn({ document: shownDoc }));
    // Another document changing does not reach the chip.
    listeners.documentChange.forEach((fn) => fn({ document: doc('vscode-remote', '/p/b.py', false) }));
    await settle();
    assert.strictEqual(calls.length, callsBefore + 1, 'typing sent more than one active file message');
    assert.deepStrictEqual(JSON.parse(calls.at(-1)[2]), { path: '/p/a.py', name: 'a.py', dirty: true });
    shownDoc.isDirty = false;
    listeners.documentSave.forEach((fn) => fn(shownDoc));
    await settle();
    assert.deepStrictEqual(JSON.parse(calls.at(-1)[2]).dirty, false, 'saving did not clear the dot');
    vscodeStub.window.activeTextEditor = undefined;
    subscriptions.forEach((s) => s.dispose());

    console.log(`diligent bridge: allowlist (${kotlinSent.size} Kotlin commands), paths, view layouts, relay branch and ready handshake OK`);
    // The relay's channel stays open, as it does in the page.
    process.exit(0);
}

main().catch((err) => {
    console.error(err);
    process.exit(1);
});
