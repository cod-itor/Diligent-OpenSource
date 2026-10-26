/**
 * Self-check for Diligent's AI suggestions (diligent-ai): the pause and
 * cancelling, the files that are never sent (with .diligentignore globs), the
 * context around the cursor, the request and answer of each provider, the
 * Wi-Fi gate, and that nothing is sent with the provider off or before the
 * user agreed to the notice.
 *
 *   node scripts/test-diligent-ai.js
 */

'use strict';

const assert = require('assert');
const fs = require('fs');
const path = require('path');
const { newestExtensionDir } = require('./lib/bundled-extension');

const DIR = newestExtensionDir('diligent.diligent-ai-');
const ai = require(path.join(DIR, 'suggest.js'));

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

/** A CancellationToken the test can cancel. */
function token() {
    const listeners = [];
    return {
        isCancellationRequested: false,
        onCancellationRequested(fn) {
            listeners.push(fn);
            return { dispose() { listeners.splice(listeners.indexOf(fn), 1); } };
        },
        cancel() {
            this.isCancellationRequested = true;
            listeners.slice().forEach((fn) => fn());
        },
    };
}

// ---- files that are never sent ---------------------------------------------

function checkExclusion() {
    for (const name of ['.env', '.env.local', 'config/.env.production', 'cert.pem', 'tls/server.KEY', 'id_rsa', '.ssh/id_ed25519.pub']) {
        assert.ok(ai.isExcluded(name), `${name} would be sent`);
    }
    for (const name of ['src/env.js', 'keys.js', 'main.py', 'my_id_card.txt', 'keyboard.ts']) {
        assert.ok(!ai.isExcluded(name), `${name} is wrongly excluded`);
    }
    assert.ok(ai.isExcluded(''), 'a document without a name is sent');

    const rules = ai.parseIgnore([
        '# comments and blank lines are ignored',
        '',
        'secrets/',
        '*.log',
        '!keep.log',
        '/build',
        'docs/**/*.md',
        'data?.csv',
        'notes/[ab].txt',
        '**/private',
    ].join('\n'));
    const excluded = ['secrets/a.js', 'src/secrets/deep/b.py', 'x.log', 'logs/y.log', 'build/out.js', 'docs/a.md',
        'docs/guide/deep/b.md', 'data1.csv', 'notes/a.txt', 'private', 'src/private/z.js'];
    const kept = ['secrets.js', 'keep.log', 'logs/keep.log', 'src/build/out.js', 'docs/a.txt', 'readme.md',
        'data10.csv', 'notes/c.txt', 'privateer.js'];
    for (const p of excluded) assert.ok(ai.isExcluded(p, rules), `${p} is not matched by .diligentignore`);
    for (const p of kept) assert.ok(!ai.isExcluded(p, rules), `${p} is wrongly matched by .diligentignore`);
    // A negation never lets a secret through.
    assert.ok(ai.isExcluded('.env', ai.parseIgnore('!.env')));
    // Windows separators read as folders.
    assert.ok(ai.isExcluded('secrets\\a.js', rules));
}

// ---- context around the cursor ---------------------------------------------

function checkContext() {
    const text = 'a'.repeat(3000) + '|' + 'b'.repeat(800);
    const { prefix, suffix } = ai.trimContext(text, 3000);
    assert.strictEqual(prefix.length, 2000);
    assert.strictEqual(suffix.length, 500);
    assert.ok(prefix.endsWith('a') && suffix.startsWith('|'));
    assert.deepStrictEqual(ai.trimContext('hello world', 5, 3, 2), { prefix: 'llo', suffix: ' w' });
    assert.deepStrictEqual(ai.trimContext('abc', 99), { prefix: 'abc', suffix: '' });
    assert.deepStrictEqual(ai.trimContext('abc', -4), { prefix: '', suffix: 'abc' });
    // Bad settings fall back to the defaults rather than sending everything.
    assert.strictEqual(ai.trimContext(text, 3000, -1, 'x').prefix.length, 2000);
    assert.strictEqual(ai.trimContext(text, 3000, 0, 0).prefix, '');
}

// ---- providers ---------------------------------------------------------------

function checkRequests() {
    const ctx = { prefix: 'def add(a, b):\n    ', suffix: '\n\nprint(add(1, 2))', languageId: 'python' };

    assert.strictEqual(ai.buildRequest({ provider: 'off' }, ctx, 'k'), null);
    assert.strictEqual(ai.buildRequest({ provider: 'laptop', laptopUrl: '' }, ctx), null, 'a laptop without an address sends');
    assert.strictEqual(ai.buildRequest({ provider: 'laptop', laptopUrl: 'file:///etc' }, ctx), null);
    assert.strictEqual(ai.buildRequest({ provider: 'apikey' }, ctx, ''), null, 'an API provider without a key sends');

    const laptop = ai.buildRequest({ provider: 'laptop', laptopUrl: 'http://192.168.1.20:11434/', maxTokens: 32 }, ctx);
    assert.strictEqual(laptop.url, 'http://192.168.1.20:11434/api/generate');
    assert.strictEqual(laptop.body.prompt, ctx.prefix);
    assert.strictEqual(laptop.body.suffix, ctx.suffix);
    assert.strictEqual(laptop.body.stream, false);
    assert.strictEqual(laptop.body.model, ai.LAPTOP_DEFAULT_MODEL);
    assert.strictEqual(laptop.body.options.num_predict, 32);
    assert.ok(!('Authorization' in laptop.headers));
    assert.strictEqual(ai.serviceHost({ provider: 'laptop', laptopUrl: 'http://192.168.1.20:11434/' }), '192.168.1.20:11434');
    assert.strictEqual(ai.parseResponse({ provider: 'laptop' }, { response: 'return a + b' }), 'return a + b');

    const codestral = ai.buildRequest({ provider: 'apikey', apiFormat: 'openai' }, ctx, 'sk-1');
    assert.strictEqual(codestral.url, 'https://codestral.mistral.ai/v1/fim/completions');
    assert.strictEqual(codestral.headers.Authorization, 'Bearer sk-1');
    assert.deepStrictEqual([codestral.body.model, codestral.body.prompt, codestral.body.suffix],
        ['codestral-latest', ctx.prefix, ctx.suffix]);
    const custom = ai.buildRequest({ provider: 'apikey', apiFormat: 'openai', endpoint: 'https://llm.example.com/v1/', model: 'm' }, ctx, 'k');
    assert.strictEqual(custom.url, 'https://llm.example.com/v1/completions');
    assert.strictEqual(custom.body.model, 'm');
    assert.strictEqual(ai.serviceHost({ provider: 'apikey', apiFormat: 'openai' }), 'codestral.mistral.ai');
    assert.strictEqual(ai.parseResponse({ provider: 'apikey', apiFormat: 'openai' }, { choices: [{ text: 'x' }] }), 'x');
    assert.strictEqual(ai.parseResponse({ provider: 'apikey', apiFormat: 'openai' }, { choices: [{ message: { content: 'y' } }] }), 'y');

    const anthropic = ai.buildRequest({ provider: 'apikey', apiFormat: 'anthropic' }, ctx, 'ak');
    assert.strictEqual(anthropic.url, 'https://api.anthropic.com/v1/messages');
    assert.strictEqual(anthropic.headers['x-api-key'], 'ak');
    assert.ok(anthropic.headers['anthropic-version']);
    assert.ok(!('Authorization' in anthropic.headers));
    assert.ok(anthropic.body.messages[0].content.includes(ctx.prefix + '<CURSOR>' + ctx.suffix));
    assert.ok(anthropic.body.system && anthropic.body.max_tokens > 0);
    assert.strictEqual(ai.parseResponse({ provider: 'apikey', apiFormat: 'anthropic' },
        { content: [{ type: 'text', text: '```python\nreturn a + b\n```' }] }), 'return a + b');

    const gemini = ai.buildRequest({ provider: 'apikey', apiFormat: 'gemini', model: 'gemini-x' }, ctx, 'gk');
    assert.strictEqual(gemini.url, 'https://generativelanguage.googleapis.com/v1beta/models/gemini-x:generateContent');
    assert.strictEqual(gemini.headers['x-goog-api-key'], 'gk');
    assert.ok(!gemini.url.includes('gk'), 'the Gemini key went into the URL');
    assert.ok(gemini.body.contents[0].parts[0].text.includes('<CURSOR>'));
    assert.strictEqual(ai.parseResponse({ provider: 'apikey', apiFormat: 'gemini' },
        { candidates: [{ content: { parts: [{ text: 'a' }, { text: 'b' }] } }] }), 'ab');

    // An unknown format is the OpenAI-compatible one, not a crash.
    assert.strictEqual(ai.apiFormat({ provider: 'apikey', apiFormat: 'nope' }), 'openai');
    for (const junk of [null, 'x', {}, { choices: 'no' }]) {
        assert.strictEqual(ai.parseResponse({ provider: 'apikey', apiFormat: 'openai' }, junk), '');
    }
    // Consent is per service and host: a new host asks again.
    assert.notStrictEqual(
        ai.consentKey({ provider: 'laptop', laptopUrl: 'http://a:11434' }),
        ai.consentKey({ provider: 'laptop', laptopUrl: 'http://b:11434' }),
    );
}

// ---- the pause and cancelling ------------------------------------------------

async function checkScheduler() {
    assert.strictEqual(ai.PAUSE_MS, 500, 'requests must wait for a 500 ms pause in typing');

    const s = ai.createScheduler({ pauseMs: 40 });
    let started = 0;
    const work = async () => { started += 1; return 'done'; };

    // Typing again before the pause: only the last one runs.
    const first = s.run(token(), work);
    await sleep(10);
    const second = s.run(token(), work);
    assert.strictEqual(await first, null);
    assert.strictEqual(started, 0, 'work started before the pause');
    assert.strictEqual(await second, 'done');
    assert.strictEqual(started, 1);

    // A cancelled token during the pause: nothing runs.
    const t = token();
    const third = s.run(t, work);
    await sleep(10);
    t.cancel();
    assert.strictEqual(await third, null);
    assert.strictEqual(started, 1);

    // A request in flight is aborted when the user types again.
    let aborted = false;
    const slow = (signal) => new Promise((resolve, reject) => {
        signal.addEventListener('abort', () => { aborted = true; reject(new Error('aborted')); });
    });
    const inFlight = s.run(token(), slow);
    await sleep(60);
    const next = s.run(token(), work);
    assert.strictEqual(await inFlight, null);
    assert.ok(aborted, 'the request in flight was not aborted');
    assert.strictEqual(await next, 'done');

    // And when VS Code cancels its token.
    aborted = false;
    const t2 = token();
    const inFlight2 = s.run(t2, slow);
    await sleep(60);
    t2.cancel();
    assert.strictEqual(await inFlight2, null);
    assert.ok(aborted);
}

// ---- the engine: every gate --------------------------------------------------

function engineWith(over = {}) {
    const log = { fetched: [], asked: 0, blocked: [], network: 0 };
    const state = {
        cfg: { provider: 'laptop', laptopUrl: 'http://10.0.0.5:11434', enabled: true, wifiOnly: true },
        consent: new Set(),
        network: 'wifi',
        key: '',
        ignore: [],
        ...over,
    };
    const engine = ai.createEngine({
        config: () => state.cfg,
        ignoreRules: () => state.ignore,
        hasConsent: (k) => state.consent.has(k),
        askConsent: () => { log.asked += 1; },
        networkType: async () => {
            log.network += 1;
            if (state.network instanceof Error) throw state.network;
            return state.network;
        },
        apiKey: async () => state.key,
        fetch: async (url, init) => {
            log.fetched.push({ url, body: JSON.parse(init.body), headers: init.headers });
            return { ok: true, status: 200, json: async () => ({ response: 'SUGGESTION' }) };
        },
        scheduler: ai.createScheduler({ pauseMs: 5 }),
        onBlocked: (r) => log.blocked.push(r),
    });
    const doc = { root: null, rel: 'src/app.js', text: 'const x = ', offset: 10, languageId: 'javascript' };
    return { engine, log, state, doc };
}

async function checkEngine() {
    // Off: nothing is asked, not even the notice.
    {
        const { engine, log, state, doc } = engineWith();
        state.cfg = { provider: 'off', enabled: true, wifiOnly: true };
        state.consent.add('whatever');
        assert.strictEqual(await engine.suggest(doc, token()), null);
        assert.deepStrictEqual([log.fetched.length, log.asked, log.network], [0, 0, 0]);
        assert.deepStrictEqual(log.blocked, ['off']);
    }
    // A provider picked but the notice not agreed to: the notice, no request.
    {
        const { engine, log, doc } = engineWith();
        assert.strictEqual(await engine.suggest(doc, token()), null);
        assert.strictEqual(log.asked, 1);
        assert.strictEqual(log.fetched.length, 0);
        assert.strictEqual(log.network, 0, 'the network was checked before consent');
        assert.deepStrictEqual(log.blocked, ['no-consent']);
    }
    const agreed = (over = {}) => {
        const e = engineWith(over);
        e.state.consent.add(ai.consentKey(e.state.cfg));
        return e;
    };
    // Agreed, on Wi-Fi: one request with the trimmed context.
    {
        const { engine, log, doc } = agreed();
        assert.strictEqual(await engine.suggest(doc, token()), 'SUGGESTION');
        assert.strictEqual(log.fetched.length, 1);
        assert.strictEqual(log.fetched[0].url, 'http://10.0.0.5:11434/api/generate');
        assert.strictEqual(log.fetched[0].body.prompt, 'const x = ');
    }
    // Wi-Fi only: mobile data, no network, or no answer from the app send nothing.
    for (const network of ['cellular', 'none', '', new Error('no bridge')]) {
        const { engine, log, doc } = agreed({ network });
        assert.strictEqual(await engine.suggest(doc, token()), null);
        assert.strictEqual(log.fetched.length, 0, `sent on ${String(network)}`);
        assert.deepStrictEqual(log.blocked, ['not-wifi']);
    }
    // With Wi-Fi only off, mobile data is allowed and not even asked.
    {
        const { engine, log, state, doc } = agreed({ network: 'cellular' });
        state.cfg = { ...state.cfg, wifiOnly: false };
        assert.strictEqual(await engine.suggest(doc, token()), 'SUGGESTION');
        assert.strictEqual(log.network, 0);
    }
    // Switched off for this project.
    {
        const { engine, log, state, doc } = agreed();
        state.cfg = { ...state.cfg, enabled: false };
        assert.strictEqual(await engine.suggest(doc, token()), null);
        assert.deepStrictEqual([log.fetched.length, log.asked], [0, 0]);
    }
    // A .env file, a key, and a file in .diligentignore never produce a request.
    for (const rel of ['.env', 'certs/dev.pem', 'id_rsa', 'secret/notes.md']) {
        const { engine, log, state, doc } = agreed();
        state.ignore = ai.parseIgnore('secret/\n');
        assert.strictEqual(await engine.suggest({ ...doc, rel }, token()), null);
        assert.strictEqual(log.fetched.length, 0, `${rel} produced a request`);
        assert.strictEqual(log.asked, 0, `${rel} raised the notice`);
        assert.deepStrictEqual(log.blocked, ['excluded']);
    }
    // API key provider: no key, no request; with one, the key is in the header.
    {
        const cfg = { provider: 'apikey', apiFormat: 'openai', enabled: true, wifiOnly: true };
        const { engine, log, state, doc } = agreed({ cfg });
        assert.strictEqual(await engine.suggest(doc, token()), null);
        assert.deepStrictEqual(log.blocked, ['no-key']);
        state.key = 'sk-test';
        await engine.suggest(doc, token());
        assert.strictEqual(log.fetched.length, 1);
        assert.strictEqual(log.fetched[0].headers.Authorization, 'Bearer sk-test');
        assert.ok(!JSON.stringify(log.fetched[0].body).includes('sk-test'), 'the key went into the body');
    }
    // Fast typing: one request, for the last keystroke.
    {
        const { engine, log, doc } = agreed();
        const a = engine.suggest({ ...doc, text: 'a', offset: 1 }, token());
        const b = engine.suggest({ ...doc, text: 'ab', offset: 2 }, token());
        assert.strictEqual(await a, null);
        assert.strictEqual(await b, 'SUGGESTION');
        assert.strictEqual(log.fetched.length, 1);
        assert.strictEqual(log.fetched[0].body.prompt, 'ab');
    }
}

// ---- the manifest ------------------------------------------------------------

function checkManifest() {
    const manifest = JSON.parse(fs.readFileSync(path.join(DIR, 'package.json'), 'utf8'));
    const nls = JSON.parse(fs.readFileSync(path.join(DIR, 'package.nls.json'), 'utf8'));
    const props = manifest.contributes.configuration.properties;
    assert.strictEqual(props['diligent.ai.provider'].default, 'off', 'suggestions must be off until the user picks');
    assert.strictEqual(props['diligent.ai.wifiOnly'].default, true);
    assert.strictEqual(props['diligent.ai.contextBefore'].default, 2000);
    assert.strictEqual(props['diligent.ai.contextAfter'].default, 500);
    assert.strictEqual(props['diligent.ai.enabled'].scope, 'resource');
    // A project's own settings file cannot point the code somewhere else.
    for (const key of ['provider', 'laptopUrl', 'apiFormat', 'endpoint']) {
        assert.strictEqual(props['diligent.ai.' + key].scope, 'application', `${key} can be set by a project`);
    }
    assert.ok(!Object.keys(props).some((k) => /key$/i.test(k) && k !== 'diligent.ai.apiFormat'), 'an API key has a setting');
    for (const m of JSON.stringify(manifest).matchAll(/"%([\w.]+)%"/g)) {
        assert.ok(m[1] in nls, `${m[1]} is not in package.nls.json`);
    }
}

// ---- activation, against a stubbed editor ----------------------------------

function checkActivation() {
    const Module = require('module');
    const registered = new Map();
    const providers = [];
    const items = [];
    const config = { provider: 'off' };
    const stub = {
        l10n: { t: (s, ...a) => s.replace(/\{(\d)\}/g, (_, i) => String(a[i])) },
        StatusBarAlignment: { Left: 1, Right: 2 },
        ConfigurationTarget: { Global: 1, Workspace: 2 },
        window: {
            createStatusBarItem: (id) => { const item = { id, show() { this.shown = true; }, dispose() {} }; items.push(item); return item; },
            onDidChangeActiveTextEditor: () => ({ dispose() {} }),
            activeTextEditor: undefined,
        },
        workspace: {
            getConfiguration: () => ({ get: (k, d) => (k in config ? config[k] : d), update: async () => {} }),
            onDidChangeConfiguration: () => ({ dispose() {} }),
        },
        languages: {
            registerInlineCompletionItemProvider: (selector, provider) => { providers.push({ selector, provider }); return { dispose() {} }; },
        },
        commands: {
            registerCommand: (id, fn) => { registered.set(id, fn); return { dispose() {} }; },
            executeCommand: async () => undefined,
        },
    };
    const resolve = Module._resolveFilename;
    Module._resolveFilename = function (request, ...rest) {
        return request === 'vscode' ? 'vscode' : resolve.call(this, request, ...rest);
    };
    require.cache.vscode = { id: 'vscode', filename: 'vscode', loaded: true, exports: stub };
    const ext = require(path.join(DIR, 'extension.js'));
    ext.activate({ subscriptions: [], globalState: { get: (k, d) => d, update: async () => {} }, secrets: { get: async () => undefined } });
    assert.strictEqual(providers.length, 1);
    assert.deepStrictEqual(providers[0].selector, { pattern: '**' }, 'not every language gets suggestions');
    assert.strictEqual(items[0].id, 'diligent.ai');
    assert.ok(items[0].shown && /AI: off/.test(items[0].text), 'the status item does not name the provider');
    const manifest = JSON.parse(fs.readFileSync(path.join(DIR, 'package.json'), 'utf8'));
    for (const c of manifest.contributes.commands) assert.ok(registered.has(c.command), `${c.command} is not registered`);
}

async function main() {
    checkExclusion();
    checkContext();
    checkRequests();
    await checkScheduler();
    await checkEngine();
    checkManifest();
    checkActivation();
    console.log('ok: diligent-ai exclusion, context, providers, pause and cancel, Wi-Fi gate and consent');
}

main().catch((err) => {
    console.error(err);
    process.exit(1);
});
