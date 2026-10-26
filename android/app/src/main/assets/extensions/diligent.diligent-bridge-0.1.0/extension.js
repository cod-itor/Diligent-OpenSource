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
]);

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
    reviveArgs,
    handleEvent,
    callBridge,
    bridgeMethodName,
    announceReady,
};
