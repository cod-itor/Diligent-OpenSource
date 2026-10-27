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
    // Sync: every open file written to disk before a project is sent.
    'workbench.action.files.saveAll',
    'workbench.action.findInFiles',
    'actions.find',
    'workbench.action.showCommands',
    // Undo: the snapshot list (diligent-core), so a beginner can go back to
    // an earlier version of the project rather than one keystroke.
    'diligent.snapshot.showList',
    // The task notification's Stop: ends one supervised task by its id (diligent-core).
    'diligent.core.stopTask',
    // Settings: the editor's own settings, from Mobile view's Settings screen.
    'workbench.action.openSettings',
    // The Setup screen: start a recipe (recipe id, project name) and stop a
    // run by its id (diligent-kit).
    'diligent.kit.add',
    'diligent.kit.cancel',
    // AI suggestions (diligent-ai): Accept and Next in Mobile view's action bar.
    'editor.action.inlineSuggest.commit',
    'editor.action.inlineSuggest.showNext',
    // The AI screen: Copilot Chat's view, and the Claude Code extension's
    // Marketplace page (called with "anthropic.claude-code").
    'workbench.action.chat.open',
    'workbench.extensions.search',
    // The AI screen's Attach: a terminal attached to one agent session,
    // registered below, which accepts diligent-ai-* session names only.
    'diligent.mobile.attachSession',
    // The action bar's Run: a terminal showing one run's output, registered
    // below, which accepts a diligent-* run name only.
    'diligent.mobile.showRun',
]);

/**
 * Mobile view's editor layout. The user's own values for these keys are
 * saved before the first one is changed and restored on the way back to
 * Desktop view.
 *
 * The editor on a phone is the code and its line numbers, nothing else: no
 * title bar, tab strip, breadcrumbs, editor actions, minimap, sticky scroll,
 * gutter icons or code lenses, no padding and thin scroll bars. The native
 * bar above it names the file (MobileChrome.kt), so the tab strip is not
 * needed for that.
 *
 * `workbench.panel.opensMaximized` is what makes the Terminal tab a full
 * screen terminal: the panel opens maximized whenever it opens, so the tab
 * never has to toggle a state it cannot read.
 */
const MOBILE_SETTINGS = Object.freeze({
    'workbench.activityBar.location': 'hidden',
    'workbench.statusBar.visible': false,
    // Nothing above the code: the title bar is empty and so hidden once the
    // menu, command center and layout controls are off.
    'window.menuBarVisibility': 'hidden',
    'window.commandCenter': false,
    'workbench.layoutControl.enabled': false,
    'workbench.editor.showTabs': 'none',
    'workbench.editor.editorActionsLocation': 'hidden',
    'breadcrumbs.enabled': false,
    'editor.minimap.enabled': false,
    'editor.stickyScroll.enabled': false,
    'editor.glyphMargin': false,
    'editor.folding': false,
    'editor.codeLens': false,
    'editor.scrollBeyondLastLine': false,
    'editor.padding.top': 0,
    'editor.padding.bottom': 0,
    'editor.overviewRulerLanes': 0,
    'editor.overviewRulerBorder': false,
    'editor.scrollbar.verticalScrollbarSize': 6,
    'editor.scrollbar.horizontalScrollbarSize': 6,
    // The line numbers stay: they are how a phone user finds a line.
    'editor.lineNumbers': 'on',
    'editor.lineNumbersMinChars': 3,
    'editor.fontSize': 15,
    'editor.lineHeight': 22,
    'workbench.panel.opensMaximized': 'always',
    // The editor follows the phone's light or dark mode, in Diligent's own
    // themes (themes/), whose background is the native bars' colour, so the
    // code and the bars around it read as one surface.
    'window.autoDetectColorScheme': true,
    'workbench.preferredLightColorTheme': 'Diligent Mobile Light',
    'workbench.preferredDarkColorTheme': 'Diligent Mobile Dark',
});

/**
 * Values an earlier version of this list wrote, for keys whose value has
 * changed since. A page load in Mobile view leaves an already saved key alone
 * (the user may have changed it there), except when it still holds the value
 * the earlier version wrote: then it takes the new one. Without this, a phone
 * that was in Mobile view before the update would keep the old tab strip and
 * themes until the next view switch.
 */
const EARLIER_MOBILE_VALUES = Object.freeze({
    'workbench.editor.showTabs': ['single'],
    'workbench.preferredLightColorTheme': ['Default Light Modern'],
    'workbench.preferredDarkColorTheme': ['Default Dark Modern'],
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
const BRIDGE_METHODS = [
    // diligent-core's supervisor: its tasks as JSON, for the notification,
    // the wake lock and Health (AndroidBridge.diligentUpdateTasks).
    'updateTasks',
    // diligent-kit: a Setup run's progress, for the Setup screen
    // (AndroidBridge.diligentKitProgress).
    'kitProgress',
    // diligent-kit's `kit doctor`: resolves with the Health checks as JSON
    // text (AndroidBridge.diligentGetHealth).
    'getHealth',
    // diligent-kit's toolchain steps: `executeCommand('diligent.bridge.installToolchain', pack)`.
    // VSCodroid's own installToolchain(name, authToken) takes the token last and
    // is not on the relay, so this reaches AndroidBridge.diligentInstallToolchain,
    // which takes the token first and calls it.
    'installToolchain',
    // diligent-kit's `kit remove`: `executeCommand('diligent.bridge.removeToolchain', pack)`,
    // AndroidBridge.diligentRemoveToolchain, which calls VSCodroid's removeToolchain.
    'removeToolchain',
    // diligent-ai's "Suggestions only on Wi-Fi": resolves with "wifi",
    // "cellular" or "none" (AndroidBridge.diligentNetworkType).
    'networkType',
];

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
    inOrder(() => execute(message.cmd, ...args)).then(undefined, (/** @type {unknown} */ err) => {
        console.warn('[Diligent] ' + message.cmd + ' failed: ' + String(err));
    });
    return { ran: message.cmd };
}

/** @type {Promise<unknown>} */
let queue = Promise.resolve();

/**
 * Runs `task` after every task queued before it has finished, and resolves
 * with its result.
 *
 * Kotlin's messages are steps of one sequence ("apply Mobile view, then show
 * the terminal"), and several of them take many awaited steps. Started side
 * by side they interleave: a Desktop apply arriving while a Mobile apply is
 * still writing settings would restore the user's values and then have the
 * rest of Mobile view's written over them, with nothing saved to restore.
 * @template T
 * @param {() => T | Thenable<T>} task
 * @returns {Promise<T>}
 */
function inOrder(task) {
    const run = queue.then(task, task);
    queue = run.then(() => undefined, () => undefined);
    return /** @type {Promise<T>} */ (run);
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
        // Already in Mobile view (a page load re-applies it): only keys not
        // yet saved are new, from a newer version of this list. The others
        // keep whatever the user changed them to while in Mobile view.
        /** @type {Record<string, unknown>} */
        const saved = applied === 'mobile' ? { ...(state.get(SAVED_SETTINGS_KEY) || {}) } : {};
        const toSet = Object.keys(MOBILE_SETTINGS).filter((key) => !(key in saved));
        for (const key of toSet) {
            const inspected = config.inspect(key);
            const current = inspected ? inspected.globalValue : undefined;
            // null for "not set", which JSON keeps and undefined would not. A
            // value already equal to Mobile view's is most likely Mobile view's
            // own, left by a page that died before it recorded the switch, so
            // it is not saved as the user's.
            saved[key] = current === undefined || sameValue(current, MOBILE_SETTINGS[key]) ? null : current;
        }
        await state.update(SAVED_SETTINGS_KEY, saved);
        await state.update(APPLIED_VIEW_KEY, 'mobile');
        for (const key of toSet) {
            await config.update(key, MOBILE_SETTINGS[key], target);
        }
        for (const key of Object.keys(EARLIER_MOBILE_VALUES)) {
            if (toSet.includes(key)) continue;
            const inspected = config.inspect(key);
            const current = inspected ? inspected.globalValue : undefined;
            if (EARLIER_MOBILE_VALUES[key].some((earlier) => sameValue(current, earlier))) {
                await config.update(key, MOBILE_SETTINGS[key], target);
            }
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

/** @param {unknown} a @param {unknown} b */
function sameValue(a, b) {
    return JSON.stringify(a) === JSON.stringify(b);
}

/**
 * The Editor tab: the editor, with no panel or side bar over it. Mobile view
 * hides the activity bar, so a side bar opened by a command would otherwise
 * have no visible way to close.
 * @param {typeof import('vscode')} api
 */
async function showEditor(api) {
    await api.commands.executeCommand('workbench.action.closePanel');
    await api.commands.executeCommand('workbench.action.closeSidebar');
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

/** The AI screen's agent sessions (tmux `diligent-ai-<agent>-<project>`). */
const AI_SESSION_NAME = /^diligent-ai-[a-z0-9_-]+$/;

/**
 * Opens a terminal attached to the tmux session [sessionName]. Anything but a
 * `diligent-ai-*` name is refused, so the text sent to the shell is always
 * this fixed command with a name that holds no quote or shell character.
 * @param {unknown} sessionName
 * @param {typeof import('vscode')} api
 * @returns {boolean} whether it attached
 */
function attachSession(sessionName, api) {
    if (typeof sessionName !== 'string' || !AI_SESSION_NAME.test(sessionName)) {
        console.warn('[Diligent] refused to attach to ' + JSON.stringify(sessionName));
        return false;
    }
    const terminal = api.window.createTerminal({ name: sessionName });
    terminal.show();
    terminal.sendText(`tmux attach -t '=${sessionName}'`);
    return true;
}

/** A run started from Mobile view (RunSession in Kotlin: `diligent-<project>-<profile>`). */
const RUN_SESSION_NAME = /^diligent-[a-z0-9_-]+$/;

/** The longest terminal title the action bar's Run gives. */
const RUN_LABEL_MAX = 40;

/**
 * The action bar's Run: a terminal that shows only what the run prints.
 *
 * Kotlin writes `~/.diligent/term/<runName>.sh` first: for a script, the
 * program itself, run in this terminal so it can read what the user types;
 * for a dev server, a follower of the run's log, the server staying in its
 * supervised tmux session. This only ever runs that file, under a name that
 * holds no quote or shell character, so nothing sent here can choose the
 * command. A terminal left from the run's last time is closed first, so Run
 * twice shows one clean run rather than two.
 * @param {unknown} runName
 * @param {unknown} label the profile's name, for the terminal's title
 * @param {typeof import('vscode')} api
 * @returns {boolean} whether it opened
 */
function showRun(runName, label, api) {
    if (typeof runName !== 'string' || !RUN_SESSION_NAME.test(runName)) {
        console.warn('[Diligent] refused to show run ' + JSON.stringify(runName));
        return false;
    }
    const shown = typeof label === 'string' ? label.replace(/[\u0000-\u001f\u007f]/g, '').trim().slice(0, RUN_LABEL_MAX) : '';
    const name = 'Run: ' + (shown || runName);
    for (const old of api.window.terminals) {
        if (old.name === name) old.dispose();
    }
    const terminal = api.window.createTerminal({ name });
    terminal.show();
    // The leading space keeps it out of the shell's history; the script
    // clears the screen, so only the run's own output stays.
    terminal.sendText(` clear; bash ~/.diligent/term/${runName}.sh`);
    return true;
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

/**
 * What the native file name chip shows: the active text editor's file, as the
 * JSON `diligentActiveFile` takes. `path` is null for an untitled file and
 * every field is empty when no text editor is active.
 * @param {{ document: { uri: { scheme: string, path: string }, isDirty: boolean } } | undefined} editor
 * @returns {string}
 */
function activeFileJson(editor) {
    if (!editor) return JSON.stringify({ path: null, name: null, dirty: false });
    const { uri, isDirty } = editor.document;
    const name = uri.path.slice(uri.path.lastIndexOf('/') + 1) || null;
    return JSON.stringify({ path: uri.scheme === 'untitled' ? null : uri.path, name, dirty: Boolean(isDirty) });
}

/**
 * Keeps Kotlin told which file is in front and whether it has unsaved
 * changes. Every typed character fires a document change, so a message goes
 * only when its text differs from the last one sent; `resend` sends again
 * anyway, for a page that has just told Kotlin it is ready.
 * @param {(method: string, args: unknown[]) => Promise<unknown>} call
 * @param {() => any} activeEditor
 */
function activeFileReporter(call, activeEditor) {
    /** @type {string | undefined} */
    let last;
    return {
        report() {
            const json = activeFileJson(activeEditor());
            if (json === last) return;
            last = json;
            call('diligentActiveFile', [json]).catch((/** @type {unknown} */ err) => {
                // Not delivered: the next change tries again rather than being dropped as a duplicate.
                last = undefined;
                console.warn('[Diligent] ' + String(err));
            });
        },
        resend() {
            last = undefined;
            this.report();
        },
    };
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
        vscode.commands.registerCommand('diligent.mobile.attachSession',
            (/** @type {unknown} */ name) => attachSession(name, vscode)),
        vscode.commands.registerCommand('diligent.mobile.showRun',
            (/** @type {unknown} */ name, /** @type {unknown} */ label) => showRun(name, label, vscode)),
    );

    for (const name of BRIDGE_METHODS) {
        context.subscriptions.push(vscode.commands.registerCommand(
            'diligent.bridge.' + name,
            (/** @type {unknown[]} */ ...args) => callBridge(bridgeMethodName(name), args),
        ));
    }

    const activeFile = activeFileReporter((method, args) => callBridge(method, args), () => vscode.window.activeTextEditor);
    const report = () => activeFile.report();
    context.subscriptions.push(
        vscode.window.onDidChangeActiveTextEditor(report),
        // Only the active document can change the chip: its dirty dot.
        vscode.workspace.onDidChangeTextDocument((e) => {
            const editor = vscode.window.activeTextEditor;
            if (editor && e.document === editor.document) report();
        }),
        vscode.workspace.onDidSaveTextDocument(report),
    );

    announceReady().then((ready) => { if (ready) activeFile.resend(); });
}

function deactivate() {}

module.exports = {
    activate,
    deactivate,
    // For scripts/test-diligent-bridge.js.
    ALLOWED_COMMANDS,
    BRIDGE_METHODS,
    MOBILE_SETTINGS,
    EARLIER_MOBILE_VALUES,
    applyView,
    inOrder,
    switchToMobileView,
    updateViewItem,
    showEditor,
    showTerminal,
    attachSession,
    showRun,
    reviveArgs,
    handleEvent,
    callBridge,
    bridgeMethodName,
    announceReady,
    activeFileJson,
    activeFileReporter,
};
