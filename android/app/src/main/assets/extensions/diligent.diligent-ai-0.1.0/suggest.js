// @ts-check
'use strict';

/**
 * The decisions behind Diligent's AI suggestions, with no `vscode` in sight so
 * scripts/test-diligent-ai.js can run them: which files are never sent, how
 * much text goes around the cursor, the request for each provider and how its
 * answer is read, the 500 ms pause and the cancelling of a request when the
 * user types again, and the gate that keeps every request behind the user's
 * choices (a provider picked, the project not switched off, the service
 * agreed to, Wi-Fi when asked).
 */

/** Typing has to pause this long before anything is asked. */
const PAUSE_MS = 500;
/** A request that takes longer than this is dropped. */
const REQUEST_TIMEOUT_MS = 15_000;

const DEFAULT_CONTEXT_BEFORE = 2000;
const DEFAULT_CONTEXT_AFTER = 500;

/** The service and model each API format reaches when the user named none. */
const API_DEFAULTS = Object.freeze({
    // Mistral Codestral's fill-in-the-middle endpoint; `/completions` is appended.
    openai: { endpoint: 'https://codestral.mistral.ai/v1/fim', model: 'codestral-latest' },
    anthropic: { endpoint: 'https://api.anthropic.com', model: 'claude-haiku-4-5' },
    gemini: { endpoint: 'https://generativelanguage.googleapis.com', model: 'gemini-2.5-flash' },
});

const LAPTOP_DEFAULT_MODEL = 'qwen2.5-coder:1.5b-base';

const CURSOR = '<CURSOR>';
const CHAT_INSTRUCTION = 'You complete code. Reply with only the text that belongs at ' + CURSOR
    + ' in the user\'s file: no explanation, no code fences, nothing repeated from before or after it.';

// ---- files that are never sent ---------------------------------------------

/**
 * Secrets by file name: `.env*`, `*.pem`, `*.key`, `id_*` (SSH keys).
 * @param {string} name a file's base name
 */
function isSecretName(name) {
    return /^\.env/i.test(name) || /\.pem$/i.test(name) || /\.key$/i.test(name) || /^id_/.test(name);
}

/**
 * One `.diligentignore` line as a rule, gitignore style: `#` comments, `!`
 * to take a file back, a trailing `/` for folders only, a `/` elsewhere ties
 * the pattern to the project folder, `*`, `?`, `**` and `[...]`.
 * @param {string} line
 * @returns {{ negate: boolean, dirOnly: boolean, anchored: boolean, re: RegExp } | null}
 */
function parseIgnoreLine(line) {
    let text = line.replace(/\r$/, '');
    if (!text.trim() || text.startsWith('#')) return null;
    text = text.replace(/(?<!\\)\s+$/, '');
    let negate = false;
    if (text.startsWith('!')) {
        negate = true;
        text = text.slice(1);
    } else if (text.startsWith('\\!') || text.startsWith('\\#')) {
        text = text.slice(1);
    }
    let dirOnly = false;
    if (text.endsWith('/')) {
        dirOnly = true;
        text = text.replace(/\/+$/, '');
    }
    const anchored = text.includes('/');
    text = text.replace(/^\//, '');
    if (!text) return null;
    return { negate, dirOnly, anchored, re: globToRegExp(text) };
}

/**
 * @param {string} glob
 * @returns {RegExp}
 */
function globToRegExp(glob) {
    let out = '';
    for (let i = 0; i < glob.length; i += 1) {
        const c = glob[i];
        if (c === '*') {
            if (glob[i + 1] === '*') {
                const before = i === 0 || glob[i - 1] === '/';
                const after = glob[i + 2] === '/' || i + 2 === glob.length;
                if (before && after) {
                    if (glob[i + 2] === '/') {
                        out += '(?:.*/)?';
                        i += 2;
                    } else {
                        out += '.*';
                        i += 1;
                    }
                    continue;
                }
                i += 1;
            }
            out += '[^/]*';
        } else if (c === '?') {
            out += '[^/]';
        } else if (c === '[') {
            const end = glob.indexOf(']', i + 1);
            if (end === -1) {
                out += '\\[';
            } else {
                let set = glob.slice(i + 1, end).replace(/\\/g, '\\\\');
                if (set.startsWith('!')) set = '^' + set.slice(1);
                out += '[' + set + ']';
                i = end;
            }
        } else if (c === '\\' && i + 1 < glob.length) {
            i += 1;
            out += glob[i].replace(/[.*+?^${}()|[\]\\/]/g, '\\$&');
        } else {
            out += c.replace(/[.*+?^${}()|[\]\\/]/g, '\\$&');
        }
    }
    return new RegExp('^' + out + '$');
}

/** @param {string} text the contents of a `.diligentignore` */
function parseIgnore(text) {
    return String(text || '').split('\n').map(parseIgnoreLine).filter((r) => r !== null);
}

/**
 * Whether a rule matches [rel] or one of the folders it sits in.
 * @param {{ dirOnly: boolean, anchored: boolean, re: RegExp }} rule
 * @param {string[]} parts the path relative to the project folder, split on `/`
 */
function ruleMatches(rule, parts) {
    for (let depth = 1; depth <= parts.length; depth += 1) {
        const isFile = depth === parts.length;
        if (rule.dirOnly && isFile) continue;
        const subject = rule.anchored ? parts.slice(0, depth).join('/') : parts[depth - 1];
        if (rule.re.test(subject)) return true;
    }
    return false;
}

/**
 * Whether a file must never be sent: a secret by name, or matched by the
 * project's `.diligentignore`. [rel] is relative to the project folder with
 * `/` separators; a file outside every project folder passes only its name.
 * @param {string} rel
 * @param {ReturnType<typeof parseIgnore>} rules
 */
function isExcluded(rel, rules = []) {
    const parts = String(rel).replace(/\\/g, '/').split('/').filter((p) => p && p !== '.');
    if (parts.length === 0) return true;
    if (isSecretName(parts[parts.length - 1])) return true;
    let excluded = false;
    for (const rule of rules) {
        if (rule.negate === excluded && ruleMatches(rule, parts)) excluded = !rule.negate;
    }
    return excluded;
}

// ---- context around the cursor ---------------------------------------------

/**
 * The text before and after the cursor that is sent: at most [before]
 * characters before and [after] after. Settings that are not a number at
 * least 0 fall back to the defaults.
 * @param {string} text the whole document
 * @param {number} offset the cursor
 */
function trimContext(text, offset, before = DEFAULT_CONTEXT_BEFORE, after = DEFAULT_CONTEXT_AFTER) {
    const b = validCount(before, DEFAULT_CONTEXT_BEFORE);
    const a = validCount(after, DEFAULT_CONTEXT_AFTER);
    const at = Math.max(0, Math.min(offset, text.length));
    return {
        prefix: text.slice(Math.max(0, at - b), at),
        suffix: text.slice(at, at + a),
    };
}

/** @param {unknown} n @param {number} fallback */
function validCount(n, fallback) {
    return typeof n === 'number' && Number.isFinite(n) && n >= 0 ? Math.floor(n) : fallback;
}

// ---- providers ---------------------------------------------------------------

/**
 * @typedef {{
 *   provider: string,
 *   laptopUrl?: string,
 *   laptopModel?: string,
 *   apiFormat?: string,
 *   endpoint?: string,
 *   model?: string,
 *   maxTokens?: number,
 * }} AiConfig
 */

/** @param {string} url */
function trimSlash(url) {
    return String(url || '').trim().replace(/\/+$/, '');
}

/**
 * Where the requests of [cfg] go, as a base URL, or '' when there is nowhere
 * to send them (provider off, or a laptop without its address).
 * @param {AiConfig} cfg
 */
function serviceBase(cfg) {
    if (cfg.provider === 'laptop') return trimSlash(cfg.laptopUrl || '');
    if (cfg.provider === 'apikey') {
        const format = apiFormat(cfg);
        return trimSlash(cfg.endpoint || API_DEFAULTS[format].endpoint);
    }
    return '';
}

/** @param {AiConfig} cfg */
function apiFormat(cfg) {
    return Object.prototype.hasOwnProperty.call(API_DEFAULTS, cfg.apiFormat || '') ? /** @type {'openai'|'anthropic'|'gemini'} */ (cfg.apiFormat) : 'openai';
}

/**
 * The host the code goes to, for the privacy notice and the consent record.
 * @param {AiConfig} cfg
 */
function serviceHost(cfg) {
    const base = serviceBase(cfg);
    if (!base) return '';
    try {
        return new URL(base).host;
    } catch {
        return '';
    }
}

/**
 * Which service it is, as an id the extension turns into words:
 * `ollama`, `openai`, `anthropic`, `gemini`, or '' when off.
 * @param {AiConfig} cfg
 */
function serviceKind(cfg) {
    if (cfg.provider === 'laptop') return 'ollama';
    if (cfg.provider === 'apikey') return apiFormat(cfg);
    return '';
}

/** What the user agreed to: the service and its host. Changing either asks again. */
function consentKey(/** @type {AiConfig} */ cfg) {
    return serviceKind(cfg) + '|' + serviceHost(cfg);
}

/**
 * The HTTP request for one suggestion, or null when [cfg] sends nothing.
 * @param {AiConfig} cfg
 * @param {{ prefix: string, suffix: string, languageId?: string }} ctx
 * @param {string} [apiKey]
 * @returns {{ url: string, headers: Record<string, string>, body: any } | null}
 */
function buildRequest(cfg, ctx, apiKey = '') {
    const base = serviceBase(cfg);
    if (!base || !/^https?:\/\//i.test(base)) return null;
    const maxTokens = validCount(cfg.maxTokens, 64) || 64;
    const json = { 'Content-Type': 'application/json' };
    if (cfg.provider === 'laptop') {
        return {
            url: base + '/api/generate',
            headers: json,
            body: {
                model: cfg.laptopModel || LAPTOP_DEFAULT_MODEL,
                prompt: ctx.prefix,
                suffix: ctx.suffix,
                stream: false,
                options: { num_predict: maxTokens, temperature: 0.2 },
            },
        };
    }
    if (cfg.provider !== 'apikey' || !apiKey) return null;
    const format = apiFormat(cfg);
    const model = cfg.model || API_DEFAULTS[format].model;
    const marked = (ctx.languageId ? 'Language: ' + ctx.languageId + '\n\n' : '') + ctx.prefix + CURSOR + ctx.suffix;
    if (format === 'anthropic') {
        return {
            url: base + '/v1/messages',
            headers: { ...json, 'x-api-key': apiKey, 'anthropic-version': '2023-06-01' },
            body: {
                model,
                max_tokens: maxTokens,
                temperature: 0.2,
                system: CHAT_INSTRUCTION,
                messages: [{ role: 'user', content: marked }],
            },
        };
    }
    if (format === 'gemini') {
        return {
            url: base + '/v1beta/models/' + encodeURIComponent(model) + ':generateContent',
            headers: { ...json, 'x-goog-api-key': apiKey },
            body: {
                systemInstruction: { parts: [{ text: CHAT_INSTRUCTION }] },
                contents: [{ role: 'user', parts: [{ text: marked }] }],
                generationConfig: { maxOutputTokens: maxTokens, temperature: 0.2 },
            },
        };
    }
    // OpenAI-compatible completions with a suffix: Mistral Codestral's FIM
    // endpoint, and any server speaking the same shape.
    return {
        url: base + '/completions',
        headers: { ...json, Authorization: 'Bearer ' + apiKey },
        body: {
            model,
            prompt: ctx.prefix,
            suffix: ctx.suffix,
            max_tokens: maxTokens,
            temperature: 0.2,
        },
    };
}

/**
 * The suggested text in a provider's answer, or '' when it has none.
 * @param {AiConfig} cfg
 * @param {any} data the parsed JSON answer
 */
function parseResponse(cfg, data) {
    if (!data || typeof data !== 'object') return '';
    let text = '';
    if (cfg.provider === 'laptop') {
        text = typeof data.response === 'string' ? data.response : '';
    } else {
        const format = apiFormat(cfg);
        if (format === 'anthropic') {
            text = (Array.isArray(data.content) ? data.content : [])
                .filter((p) => p && p.type === 'text' && typeof p.text === 'string')
                .map((p) => p.text).join('');
        } else if (format === 'gemini') {
            const parts = data.candidates && data.candidates[0] && data.candidates[0].content
                && data.candidates[0].content.parts;
            text = (Array.isArray(parts) ? parts : []).map((p) => (p && typeof p.text === 'string' ? p.text : '')).join('');
        } else {
            const choice = Array.isArray(data.choices) ? data.choices[0] : null;
            if (choice && typeof choice.text === 'string') text = choice.text;
            else if (choice && choice.message && typeof choice.message.content === 'string') text = choice.message.content;
        }
    }
    return stripFences(text);
}

/** A chat model's answer wrapped in a code fence, unwrapped. @param {string} text */
function stripFences(text) {
    const m = /^\s*```[\w+-]*\n([\s\S]*?)\n?```\s*$/.exec(text);
    return m ? m[1] : text;
}

// ---- the pause and cancelling ------------------------------------------------

/**
 * Runs one piece of work per suggestion, only after [pauseMs] without a newer
 * one, and aborts the one before when a newer one starts or its token is
 * cancelled: VS Code cancels the token when the user types again.
 * @param {{ pauseMs?: number, setTimer?: typeof setTimeout, clearTimer?: typeof clearTimeout }} [opts]
 */
function createScheduler(opts = {}) {
    const pauseMs = opts.pauseMs === undefined ? PAUSE_MS : opts.pauseMs;
    const setTimer = opts.setTimer || setTimeout;
    const clearTimer = opts.clearTimer || clearTimeout;
    /** @type {AbortController | null} */
    let current = null;
    return {
        /**
         * @template T
         * @param {{ isCancellationRequested: boolean, onCancellationRequested(fn: () => void): { dispose(): void } } | undefined} token
         * @param {(signal: AbortSignal) => Promise<T>} work
         * @returns {Promise<T | null>}
         */
        async run(token, work) {
            if (current) current.abort();
            const controller = new AbortController();
            current = controller;
            const sub = token ? token.onCancellationRequested(() => controller.abort()) : null;
            try {
                if (token && token.isCancellationRequested) return null;
                const paused = await new Promise((resolve) => {
                    const timer = setTimer(() => resolve(true), pauseMs);
                    controller.signal.addEventListener('abort', () => {
                        clearTimer(timer);
                        resolve(false);
                    }, { once: true });
                });
                if (!paused || controller.signal.aborted) return null;
                return await work(controller.signal);
            } catch (err) {
                if (controller.signal.aborted) return null;
                throw err;
            } finally {
                if (sub) sub.dispose();
                if (current === controller) current = null;
            }
        },
        /** Aborts whatever is waiting or in flight. */
        cancel() {
            if (current) current.abort();
        },
    };
}

// ---- the whole request -------------------------------------------------------

/**
 * Why nothing may be sent for this document right now, before the pause, or
 * null. Cheap checks only: nothing here talks to anything.
 * @param {{ cfg: AiConfig, enabled: boolean, rel: string, rules: ReturnType<typeof parseIgnore> }} s
 */
function blockedBeforePause(s) {
    if (!s.cfg || (s.cfg.provider !== 'laptop' && s.cfg.provider !== 'apikey')) return 'off';
    if (!s.enabled) return 'disabled';
    if (isExcluded(s.rel, s.rules)) return 'excluded';
    if (!serviceBase(s.cfg)) return 'no-service';
    return null;
}

/**
 * The engine the extension wires to VS Code. Every outside effect comes in
 * through [deps], so the test can see exactly what was sent and when.
 *
 * @param {{
 *   config(): AiConfig & { enabled: boolean, wifiOnly: boolean, contextBefore?: number, contextAfter?: number },
 *   ignoreRules(root: string | null): ReturnType<typeof parseIgnore>,
 *   hasConsent(key: string): boolean,
 *   askConsent(cfg: AiConfig): void,
 *   networkType(): Promise<string>,
 *   apiKey(format: string): Promise<string>,
 *   fetch(url: string, init: any): Promise<{ ok: boolean, status: number, json(): Promise<any> }>,
 *   scheduler?: ReturnType<typeof createScheduler>,
 *   onBlocked?(reason: string): void,
 * }} deps
 */
function createEngine(deps) {
    const scheduler = deps.scheduler || createScheduler();
    const blocked = (/** @type {string} */ reason) => {
        if (deps.onBlocked) deps.onBlocked(reason);
        return null;
    };
    return {
        scheduler,
        /**
         * One suggestion for the document, or null.
         * @param {{ root: string | null, rel: string, text: string, offset: number, languageId?: string }} doc
         * @param {any} token
         * @returns {Promise<string | null>}
         */
        async suggest(doc, token) {
            const cfg = deps.config();
            const rules = deps.ignoreRules(doc.root);
            const early = blockedBeforePause({ cfg, enabled: cfg.enabled, rel: doc.rel, rules });
            if (early) {
                // A newer keystroke in a file that sends nothing still ends
                // the request from the file before.
                scheduler.cancel();
                return blocked(early);
            }
            return scheduler.run(token, async (signal) => {
                if (!deps.hasConsent(consentKey(cfg))) {
                    deps.askConsent(cfg);
                    return blocked('no-consent');
                }
                if (cfg.wifiOnly) {
                    let type = '';
                    try {
                        type = await deps.networkType();
                    } catch {
                        type = '';
                    }
                    if (signal.aborted) return null;
                    if (type !== 'wifi') return blocked('not-wifi');
                }
                const key = cfg.provider === 'apikey' ? await deps.apiKey(apiFormat(cfg)) : '';
                if (signal.aborted) return null;
                if (cfg.provider === 'apikey' && !key) return blocked('no-key');
                const ctx = trimContext(doc.text, doc.offset, cfg.contextBefore, cfg.contextAfter);
                const request = buildRequest(cfg, { ...ctx, languageId: doc.languageId }, key);
                if (!request) return blocked('no-service');
                const own = new AbortController();
                const abortAll = () => own.abort();
                const timeout = setTimeout(abortAll, REQUEST_TIMEOUT_MS);
                signal.addEventListener('abort', abortAll, { once: true });
                try {
                    const res = await deps.fetch(request.url, {
                        method: 'POST',
                        headers: request.headers,
                        body: JSON.stringify(request.body),
                        signal: own.signal,
                    });
                    if (!res.ok) return blocked('http-' + res.status);
                    const text = parseResponse(cfg, await res.json());
                    return text || null;
                } finally {
                    clearTimeout(timeout);
                    signal.removeEventListener('abort', abortAll);
                }
            });
        },
    };
}

module.exports = {
    PAUSE_MS,
    API_DEFAULTS,
    LAPTOP_DEFAULT_MODEL,
    isSecretName,
    parseIgnore,
    isExcluded,
    trimContext,
    serviceBase,
    serviceHost,
    serviceKind,
    consentKey,
    apiFormat,
    buildRequest,
    parseResponse,
    createScheduler,
    blockedBeforePause,
    createEngine,
};
