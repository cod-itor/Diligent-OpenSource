// @ts-check

/**
 * Diligent Bridge (browser)
 *
 * Runs in the web extension host, beside VSCodroid's saf-bridge, and carries
 * Diligent's messages both ways between Kotlin and the editor:
 *
 * - Kotlin to editor. Kotlin posts `{cmd, args}` on
 *   `BroadcastChannel('diligent-events')` (EditorChannel.kt), and this runs
 *   `vscode.commands.executeCommand(cmd, ...args)`, for the commands in
 *   ALLOWED_COMMANDS only. The allowlist is the security boundary: any web
 *   extension in the page can post on the same channel, so a command missing
 *   from it is refused rather than run.
 *
 * - Editor to Kotlin. Diligent's bridge methods are reached through VSCodroid's
 *   relay on `BroadcastChannel('vscodroid-bridge')`, whose one Diligent branch
 *   forwards `diligentXxx` to `AndroidBridge.diligentXxx(token, ...args)`.
 *   Code in the Node extension host, which has no BroadcastChannel to the page,
 *   calls `diligent.bridge.<name>` commands registered here instead.
 *
 * On activation it tells Kotlin it is listening (`diligentEditorReady`), which
 * is when Kotlin sends what it held during the cold start.
 *
 * It also owns the editor's side of the view switch (`diligent.view.apply`):
 * Mobile view hides the editor's own bars and menus with settings and
 * commands, after saving the user's values, and Desktop view puts those
 * values back. Switching never reloads the page.
 */

const vscode = require('vscode');

const EVENTS_CHANNEL = 'diligent-events';
const RELAY_CHANNEL = 'vscodroid-bridge';

/**
 * Editor commands Kotlin may run, and nothing else. Add a command here in the
 * same change that makes Kotlin send it.
 */
const ALLOWED_COMMANDS = new Set([
    // Files tab: open the tapped file.
    'vscode.open',
    // The view switch and the Editor and Terminal tabs, registered below.
    'diligent.view.apply',
    'diligent.mobile.showEditor',
    'diligent.mobile.showTerminal',
    // Mobile view's action bar.
    'workbench.action.files.save',
    'workbench.action.findInFiles',
    'workbench.action.showCommands',
    'undo',
]);

/**
 * Mobile view's editor layout. The user's own values for these keys are
 * saved before the first one is changed and restored on the way back to
 * Desktop view.
 *
 * `workbench.panel.opensMaximized` is what makes the Terminal tab a full
 * screen terminal: the panel opens maximized whenever it opens, so the tab
 * never has to toggle a state it cannot read.
 */
const MOBILE_SETTINGS = Object.freeze({
    'workbench.activityBar.location': 'hidden',
    'workbench.statusBar.visible': false,
    'workbench.editor.showTabs': 'single',
    'breadcrumbs.enabled': false,
    'editor.fontSize': 15,
    'workbench.panel.opensMaximized': 'always',
});

/** Commands that clear the editor's side areas when Mobile view comes in. */
const MOBILE_CLOSE_COMMANDS = [
    'workbench.action.closeSidebar',
    'workbench.action.closePanel',
    'workbench.action.closeAuxiliaryBar',
];

/** globalState keys: which layout is applied, and the user's values it replaced. */
const APPLIED_VIEW_KEY = 'diligent.appliedView';
const SAVED_SETTINGS_KEY = 'diligent.savedDesktopSettings';

/**
 * Bridge methods exposed to the Node extension host as `diligent.bridge.<name>`,
 * each forwarding to `AndroidBridge.diligent<Name>`.
 * @type {string[]}
 */
const BRIDGE_METHODS = [];

/** How long a bridge call may take before it is reported as unanswered. */
const BRIDGE_TIMEOUT_MS = 5000;

/** How often, and how far apart, to try telling Kotlin this extension is listening. */
const READY_ATTEMPTS = 30;
const READY_RETRY_MS = 1000;

/**
 * Turns the arguments Kotlin sent into what the command expects: a
 * `{"$path": "/abs/path"}` becomes a URI on the editor server, the same scheme
 * and authority as this extension's own location, since both live there.
 * @param {unknown[]} args
 * @param {import('vscode').Uri} base
 * @returns {unknown[]}
 */
function reviveArgs(args, base) {
    return args.map((arg) => {
        if (arg && typeof arg === 'object' && typeof (/** @type {any} */ (arg)).$path === 'string') {
            return base.with({ path: /** @type {any} */ (arg).$path, query: '', fragment: '' });
        }
        return arg;
    });
}

/**
 * Handles one message from Kotlin. Returns what it ran, or why it did not, so a
 * test can see the decision without a real editor.
 * @param {unknown} data
 * @param {import('vscode').Uri} base
 * @param {(cmd: string, ...args: unknown[]) => Thenable<unknown>} execute
 * @returns {{ran: string} | {refused: string}}
 */
function handleEvent(data, base, execute) {
    const message = /** @type {any} */ (data);
    if (!message || typeof message.cmd !== 'string') return { refused: 'not a message' };
    if (!ALLOWED_COMMANDS.has(message.cmd)) return { refused: message.cmd };
    const args = Array.isArray(message.args) ? reviveArgs(message.args, base) : [];
    Promise.resolve(execute(message.cmd, ...args)).then(undefined, (/** @type {unknown} */ err) => {
        console.warn('[Diligent] ' + message.cmd + ' failed: ' + String(err));
    });
    return { ran: message.cmd };
}

/**
 * Calls `AndroidBridge.<method>` through the relay and resolves with its answer.
 * @param {string} method a `diligentXxx` bridge method name
 * @param {unknown[]} args
 * @param {number} [timeoutMs]
 * @returns {Promise<unknown>}
 */
function callBridge(method, args, timeoutMs = BRIDGE_TIMEOUT_MS) {
    return new Promise((resolve, reject) => {
        const id = 'diligent-' + Math.random().toString(36).slice(2);
        const channel = new BroadcastChannel(RELAY_CHANNEL);
        const timer = setTimeout(() => {
            channel.close();
            reject(new Error('Diligent: no answer from the app for ' + method));
        }, timeoutMs);
        channel.onmessage = (e) => {
            const reply = e.data || {};
            if (reply.id !== id) return;
            clearTimeout(timer);
            channel.close();
            if (reply.ok) resolve(reply.data);
            else reject(new Error(reply.error || 'Diligent: ' + method + ' failed'));
        };
        channel.postMessage({ cmd: method, id, args });
    });
}

/**
 * Applies the editor layout of `view` ('mobile' or 'desktop').
 *
 * Entering Mobile view saves the user's own (user level) values of the keys
 * it changes, once: applying it again on the next page load must not save
 * Mobile view's values over the user's. Entering Desktop view restores them,
 * removing a key the user had never set rather than writing its default.
 * Anything else is ignored.
 *
 * @param {string} view
 * @param {{ get(key: string): any, update(key: string, value: any): Thenable<void> }} state globalState
 * @param {typeof import('vscode')} api
 */
async function applyView(view, state, api) {
    const config = api.workspace.getConfiguration();
    const target = api.ConfigurationTarget.Global;
    const applied = state.get(APPLIED_VIEW_KEY);
    if (view === 'mobile') {
        if (applied !== 'mobile') {
            /** @type {Record<string, unknown>} */
            const saved = {};
            for (const key of Object.keys(MOBILE_SETTINGS)) {
                const inspected = config.inspect(key);
                // null for "not set", which JSON keeps and undefined would not.
                saved[key] = inspected && inspected.globalValue !== undefined ? inspected.globalValue : null;
            }
            await state.update(SAVED_SETTINGS_KEY, saved);
            await state.update(APPLIED_VIEW_KEY, 'mobile');
        }
        for (const [key, value] of Object.entries(MOBILE_SETTINGS)) {
            await config.update(key, value, target);
        }
        for (const command of MOBILE_CLOSE_COMMANDS) {
            await api.commands.executeCommand(command);
        }
        return true;
    }
    if (view === 'desktop') {
        if (applied === 'mobile') {
            const saved = state.get(SAVED_SETTINGS_KEY) || {};
            for (const key of Object.keys(MOBILE_SETTINGS)) {
                const value = saved[key];
                await config.update(key, value === null || value === undefined ? undefined : value, target);
            }
            await state.update(SAVED_SETTINGS_KEY, undefined);
        }
        await state.update(APPLIED_VIEW_KEY, 'desktop');
        return true;
    }
    return false;
}

/**
 * The Editor tab: the editor, with no panel over it.
 * @param {typeof import('vscode')} api
 */
async function showEditor(api) {
    await api.commands.executeCommand('workbench.action.closePanel');
    await api.commands.executeCommand('workbench.action.focusActiveEditorGroup');
}

/**
 * The Terminal tab: the integrated terminal, full screen. Closing the panel
 * first makes the focus open it again, which Mobile view's
 * `workbench.panel.opensMaximized` makes maximized.
 * @param {typeof import('vscode')} api
 */
async function showTerminal(api) {
    await api.commands.executeCommand('workbench.action.closePanel');
    await api.commands.executeCommand('workbench.action.terminal.focus');
}

/**
 * Asks the app to switch to Mobile view. Kotlin stores the choice, brings in
 * its native screens and sends `diligent.view.apply` back for the editor's
 * side, so this does not change the layout itself.
 * @param {(method: string, args: unknown[]) => Promise<unknown>} call
 */
function switchToMobileView(call) {
    return call('diligentSetView', ['mobile']);
}

/**
 * The status bar item that offers Mobile view. It only matters in Desktop
 * view, since Mobile view hides the status bar, and it is hidden there too so
 * a user who shows the status bar again does not see an offer for the view
 * they are in.
 * @param {{ show(): void, hide(): void }} item
 * @param {unknown} appliedView
 */
function updateViewItem(item, appliedView) {
    if (appliedView === 'mobile') item.hide();
    else item.show();
}

/** `getView` -> `diligentGetView`. */
function bridgeMethodName(/** @type {string} */ name) {
    return 'diligent' + name.charAt(0).toUpperCase() + name.slice(1);
}

/**
 * Tells Kotlin this page's extension is listening. The relay is injected after
 * the page loads, which can be later than this activation, so a refusal or a
 * timeout is tried again a few times.
 */
async function announceReady() {
    for (let attempt = 1; attempt <= READY_ATTEMPTS; attempt += 1) {
        try {
            await callBridge('diligentEditorReady', [], READY_RETRY_MS);
            return true;
        } catch (err) {
            if (attempt === READY_ATTEMPTS) {
                console.warn('[Diligent] the app did not answer: ' + String(err));
            }
        }
    }
    return false;
}

/** @param {import('vscode').ExtensionContext} context */
function activate(context) {
    const events = new BroadcastChannel(EVENTS_CHANNEL);
    events.onmessage = (e) => {
        const outcome = handleEvent(e.data, context.extensionUri, vscode.commands.executeCommand);
        if ('refused' in outcome) console.warn('[Diligent] refused ' + outcome.refused);
    };
    context.subscriptions.push({ dispose: () => events.close() });

    /** @param {(...a: any[]) => Promise<unknown>} fn */
    const logged = (fn) => (/** @type {any[]} */ ...a) => fn(...a).catch((/** @type {unknown} */ err) => {
        console.warn('[Diligent] ' + String(err));
    });
    const viewItem = vscode.window.createStatusBarItem(
        'diligent.mobileView', vscode.StatusBarAlignment.Left, 1000);
    viewItem.name = vscode.l10n.t('Diligent: Mobile view');
    viewItem.text = '$(device-mobile) ' + vscode.l10n.t('Mobile view');
    viewItem.tooltip = vscode.l10n.t('Switch to Diligent\'s touch-first Mobile view. Open files and terminals stay as they are.');
    viewItem.command = 'diligent.switchToMobileView';
    updateViewItem(viewItem, context.globalState.get(APPLIED_VIEW_KEY));

    context.subscriptions.push(
        viewItem,
        vscode.commands.registerCommand('diligent.view.apply',
            logged(async (/** @type {string} */ view) => {
                await applyView(view, context.globalState, vscode);
                updateViewItem(viewItem, context.globalState.get(APPLIED_VIEW_KEY));
            })),
        vscode.commands.registerCommand('diligent.switchToMobileView',
            logged(() => switchToMobileView((method, args) => callBridge(method, args)))),
        vscode.commands.registerCommand('diligent.mobile.showEditor', logged(() => showEditor(vscode))),
        vscode.commands.registerCommand('diligent.mobile.showTerminal', logged(() => showTerminal(vscode))),
    );

    for (const name of BRIDGE_METHODS) {
        context.subscriptions.push(vscode.commands.registerCommand(
            'diligent.bridge.' + name,
            (/** @type {unknown[]} */ ...args) => callBridge(bridgeMethodName(name), args),
        ));
    }

    announceReady();
}

function deactivate() {}

module.exports = {
    activate,
    deactivate,
    // For scripts/test-diligent-bridge.js.
    ALLOWED_COMMANDS,
    BRIDGE_METHODS,
    MOBILE_SETTINGS,
    applyView,
    switchToMobileView,
    updateViewItem,
    showEditor,
    showTerminal,
    reviveArgs,
    handleEvent,
    callBridge,
    bridgeMethodName,
    announceReady,
};
