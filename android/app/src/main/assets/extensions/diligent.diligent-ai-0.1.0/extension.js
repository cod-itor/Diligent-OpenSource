// @ts-check
'use strict';

/**
 * Diligent AI (Node extension host)
 *
 * Suggestions while typing, in both views: an InlineCompletionItemProvider
 * for every language that asks the service the user picked
 * (`diligent.ai.provider`) for the text at the cursor.
 *
 * - `off` (the default) sends nothing, ever. Nothing is sent before the user
 *   picks a provider and agrees to the notice naming the service and its host.
 * - `laptop`: Ollama on the user's laptop, `POST /api/generate` with `prompt`
 *   and `suffix`.
 * - `apikey`: the user's own key for an OpenAI-compatible endpoint (Mistral
 *   Codestral's fill-in-the-middle by default), Anthropic or Gemini. Keys live
 *   only in SecretStorage.
 *
 * A request waits for a 500 ms pause in typing and is aborted when the user
 * types again. "Suggestions only on Wi-Fi" asks the app for the network type
 * through `diligent.bridge.networkType` (diligent-bridge, AndroidBridge
 * .diligentNetworkType). `.env*`, `*.pem`, `*.key`, `id_*` and files matched
 * by the project's `.diligentignore` are never sent. suggest.js holds every
 * decision; this file wires it to the editor.
 */

const vscode = require('vscode');
const fs = require('fs');
const path = require('path');
const ai = require('./suggest');

const NETWORK_COMMAND = 'diligent.bridge.networkType';
/** How long a network answer is trusted, so a burst of typing asks once. */
const NETWORK_TTL_MS = 5_000;
const CONSENT_KEY = 'diligent.ai.consented';
const SECRET_PREFIX = 'diligent.ai.apiKey.';

/** @param {vscode.Uri | undefined} [resource] */
function readConfig(resource) {
    const c = vscode.workspace.getConfiguration('diligent.ai', resource);
    return {
        provider: c.get('provider', 'off'),
        laptopUrl: c.get('laptopUrl', ''),
        laptopModel: c.get('laptopModel', ''),
        apiFormat: c.get('apiFormat', 'openai'),
        endpoint: c.get('endpoint', ''),
        model: c.get('model', ''),
        maxTokens: c.get('maxTokens', 64),
        enabled: c.get('enabled', true),
        wifiOnly: c.get('wifiOnly', true),
        contextBefore: c.get('contextBefore', 2000),
        contextAfter: c.get('contextAfter', 500),
    };
}

/** The service's name, in words. @param {string} kind */
function serviceName(kind) {
    switch (kind) {
        case 'ollama': return vscode.l10n.t('Ollama on your laptop');
        case 'anthropic': return vscode.l10n.t('Anthropic');
        case 'gemini': return vscode.l10n.t('Google Gemini');
        case 'openai': return vscode.l10n.t('an OpenAI-compatible service');
        default: return vscode.l10n.t('off');
    }
}

/** `.diligentignore` rules per project folder, read again when the file changes. */
function createIgnoreCache() {
    /** @type {Map<string, { mtime: number, rules: ReturnType<typeof ai.parseIgnore> }>} */
    const cache = new Map();
    return (/** @type {string | null} */ root) => {
        if (!root) return [];
        const file = path.join(root, '.diligentignore');
        let mtime = -1;
        try {
            mtime = fs.statSync(file).mtimeMs;
        } catch {
            cache.delete(root);
            return [];
        }
        const hit = cache.get(root);
        if (hit && hit.mtime === mtime) return hit.rules;
        let rules = [];
        try {
            rules = ai.parseIgnore(fs.readFileSync(file, 'utf8'));
        } catch {
            rules = [];
        }
        cache.set(root, { mtime, rules });
        return rules;
    };
}

/** @param {vscode.ExtensionContext} context */
function activate(context) {
    const ignoreRules = createIgnoreCache();
    let network = { at: 0, type: '' };
    let asking = false;
    /** @type {vscode.Uri | undefined} */
    let currentResource;

    const status = vscode.window.createStatusBarItem('diligent.ai', vscode.StatusBarAlignment.Right, 100);
    status.name = vscode.l10n.t('Diligent AI suggestions');
    status.command = 'diligent.ai.chooseProvider';
    const updateStatus = () => {
        const cfg = readConfig(currentResource);
        const kind = ai.serviceKind(cfg);
        if (!kind) {
            status.text = '$(sparkle) ' + vscode.l10n.t('AI: off');
            status.tooltip = vscode.l10n.t('AI suggestions are off. Pick a provider to turn them on.');
        } else {
            const host = ai.serviceHost(cfg);
            status.text = '$(sparkle) ' + vscode.l10n.t('AI: {0}', serviceName(kind))
                + (cfg.enabled ? '' : ' ' + vscode.l10n.t('(off here)'));
            status.tooltip = vscode.l10n.t('Suggestions come from {0} at {1}.', serviceName(kind), host || '?');
        }
        status.show();
    };

    const hasConsent = (/** @type {string} */ key) => {
        const list = context.globalState.get(CONSENT_KEY, []);
        return Array.isArray(list) && list.includes(key);
    };

    /** The first-use notice: which service gets the code, and its host. */
    const askConsent = (/** @type {ai.AiConfig} */ cfg) => {
        if (asking) return;
        asking = true;
        const key = ai.consentKey(cfg);
        const allow = vscode.l10n.t('Allow');
        const off = vscode.l10n.t('Turn off');
        const extra = cfg.provider === 'laptop' && !/^(localhost|127\.)/.test(ai.serviceHost(cfg))
            ? ' ' + vscode.l10n.t('Ollama only listens to its own computer by default. Starting it with OLLAMA_HOST=0.0.0.0 opens it to everyone on that Wi-Fi.')
            : '';
        vscode.window.showInformationMessage(
            vscode.l10n.t(
                'AI suggestions will send the code around your cursor to {0} at {1}. Files like .env, keys and those in .diligentignore are never sent.',
                serviceName(ai.serviceKind(cfg)), ai.serviceHost(cfg),
            ) + extra,
            allow, off,
        ).then(async (choice) => {
            if (choice === allow) {
                const list = context.globalState.get(CONSENT_KEY, []);
                await context.globalState.update(CONSENT_KEY, [...(Array.isArray(list) ? list : []), key]);
            } else if (choice === off) {
                await vscode.workspace.getConfiguration('diligent.ai')
                    .update('provider', 'off', vscode.ConfigurationTarget.Global);
            }
        }).then(() => { asking = false; }, () => { asking = false; });
    };

    const engine = ai.createEngine({
        config: () => readConfig(currentResource),
        ignoreRules,
        hasConsent,
        askConsent,
        networkType: async () => {
            const now = Date.now();
            if (network.type && now - network.at < NETWORK_TTL_MS) return network.type;
            const type = String(await vscode.commands.executeCommand(NETWORK_COMMAND));
            network = { at: now, type };
            return type;
        },
        apiKey: async (format) => (await context.secrets.get(SECRET_PREFIX + format)) || '',
        fetch: (url, init) => fetch(url, init),
    });

    /** @type {vscode.InlineCompletionItemProvider} */
    const provider = {
        async provideInlineCompletionItems(document, position, _ctx, token) {
            currentResource = document.uri;
            const folder = vscode.workspace.getWorkspaceFolder(document.uri);
            const root = folder && folder.uri.scheme === 'file' ? folder.uri.fsPath : null;
            const rel = folder ? path.relative(folder.uri.fsPath, document.uri.fsPath) : path.basename(document.uri.fsPath);
            let text;
            try {
                text = await engine.suggest({
                    root,
                    rel: rel.startsWith('..') ? path.basename(document.uri.fsPath) : rel,
                    text: document.getText(),
                    offset: document.offsetAt(position),
                    languageId: document.languageId,
                }, token);
            } catch (err) {
                console.warn('[Diligent AI] ' + String(err));
                return [];
            }
            if (!text || token.isCancellationRequested) return [];
            return [new vscode.InlineCompletionItem(text, new vscode.Range(position, position))];
        },
    };

    context.subscriptions.push(
        status,
        vscode.languages.registerInlineCompletionItemProvider({ pattern: '**' }, provider),
        vscode.workspace.onDidChangeConfiguration((e) => {
            if (e.affectsConfiguration('diligent.ai')) updateStatus();
        }),
        vscode.window.onDidChangeActiveTextEditor((editor) => {
            if (editor) currentResource = editor.document.uri;
            updateStatus();
        }),
        vscode.commands.registerCommand('diligent.ai.chooseProvider', () => chooseProvider(context)),
        vscode.commands.registerCommand('diligent.ai.setApiKey', () => setApiKey(context)),
        vscode.commands.registerCommand('diligent.ai.toggleProject', async () => {
            const config = vscode.workspace.getConfiguration('diligent.ai', currentResource);
            const now = config.get('enabled', true);
            await config.update('enabled', !now, vscode.ConfigurationTarget.Workspace);
        }),
    );
    if (vscode.window.activeTextEditor) currentResource = vscode.window.activeTextEditor.document.uri;
    updateStatus();
}

/** Picks where suggestions come from. @param {vscode.ExtensionContext} context */
async function chooseProvider(context) {
    const config = vscode.workspace.getConfiguration('diligent.ai');
    const target = vscode.ConfigurationTarget.Global;
    const picked = await vscode.window.showQuickPick([
        { label: vscode.l10n.t('Off'), description: vscode.l10n.t('No suggestions'), id: 'off' },
        { label: vscode.l10n.t('Laptop (Ollama)'), description: vscode.l10n.t('A model running on your own computer'), id: 'laptop' },
        { label: vscode.l10n.t('My API key'), description: vscode.l10n.t('Codestral, OpenAI-compatible, Anthropic or Gemini'), id: 'apikey' },
    ], { placeHolder: vscode.l10n.t('Where should AI suggestions come from?') });
    if (!picked) return;
    if (picked.id === 'laptop') {
        const url = await vscode.window.showInputBox({
            prompt: vscode.l10n.t('Ollama address on your laptop, like http://192.168.1.20:11434. Ollama only listens to its own computer by default; OLLAMA_HOST=0.0.0.0 opens it to everyone on that Wi-Fi.'),
            value: config.get('laptopUrl', '') || 'http://',
            validateInput: (v) => (/^https?:\/\/[^/\s]+/.test(v.trim()) ? null : vscode.l10n.t('Enter an address starting with http:// or https://')),
        });
        if (!url) return;
        await config.update('laptopUrl', url.trim(), target);
    }
    if (picked.id === 'apikey') {
        const format = await pickFormat(config.get('apiFormat', 'openai'));
        if (!format) return;
        await config.update('apiFormat', format, target);
        if (!(await context.secrets.get(SECRET_PREFIX + format))) {
            if (!(await setApiKey(context, format))) return;
        }
    }
    await config.update('provider', picked.id, target);
}

/** @param {string} current */
async function pickFormat(current) {
    const items = [
        { label: vscode.l10n.t('OpenAI-compatible'), description: vscode.l10n.t('Mistral Codestral by default, or any endpoint you set'), id: 'openai' },
        { label: 'Anthropic', id: 'anthropic' },
        { label: 'Google Gemini', id: 'gemini' },
    ].map((i) => ({ ...i, picked: i.id === current }));
    const picked = await vscode.window.showQuickPick(items, { placeHolder: vscode.l10n.t('Which service is the key for?') });
    return picked ? picked.id : '';
}

/**
 * Stores a key in SecretStorage, never in settings.
 * @param {vscode.ExtensionContext} context
 * @param {string} [format]
 */
async function setApiKey(context, format) {
    const chosen = format || await pickFormat(vscode.workspace.getConfiguration('diligent.ai').get('apiFormat', 'openai'));
    if (!chosen) return false;
    const key = await vscode.window.showInputBox({
        prompt: vscode.l10n.t('Your API key. It is kept in the editor\'s secret storage on this phone, never in a settings file.'),
        password: true,
        ignoreFocusOut: true,
    });
    if (!key) return false;
    await context.secrets.store(SECRET_PREFIX + chosen, key.trim());
    return true;
}

function deactivate() {}

module.exports = { activate, deactivate, readConfig, createIgnoreCache };
