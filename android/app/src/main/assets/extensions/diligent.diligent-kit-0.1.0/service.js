// @ts-check
'use strict';

/**
 * What the socket and the editor commands share: one engine, one run at a
 * time, and every run's progress sent to the app.
 *
 * The app is reached through commands the web-side diligent-bridge extension
 * registers (`diligent.bridge.<name>` to `AndroidBridge.diligent<Name>`):
 *
 * - `kitProgress(json)`: every progress message, as a JSON string, throttled
 *   to about four output lines a second by the engine.
 * - `getHealth()`: answers a JSON string `{"checks": [...]}`.
 * - `installToolchain(pack)` and `removeToolchain(pack)`: start the app's own
 *   install or removal. Neither reports completion; see engine.js.
 */

const engine = require('./engine');

const PROGRESS_COMMAND = 'diligent.bridge.kitProgress';
const HEALTH_COMMAND = 'diligent.bridge.getHealth';
const INSTALL_COMMAND = 'diligent.bridge.installToolchain';
const REMOVE_COMMAND = 'diligent.bridge.removeToolchain';
const HEALTH_TIMEOUT_MS = 8_000;

/**
 * @param {{
 *   extensionDir: string, home: string, projectsDir: string,
 *   executeCommand: (cmd: string, ...args: unknown[]) => PromiseLike<unknown>,
 *   log?: (msg: string) => void,
 *   runOptions?: Record<string, unknown>,
 * }} o
 */
function createService(o) {
    const log = o.log || (() => {});
    const loaded = engine.loadRecipes(o.extensionDir);
    for (const p of loaded.problems) log('recipe skipped: ' + p);
    const recipes = new Map(loaded.recipes.map((r) => [r.id, r]));
    /** @type {{ runId: string, signal: { cancelled: boolean, onCancel?: () => void } } | null} */
    let active = null;

    /**
     * Sends the contract's fields only; `tail` and `run` are for the terminal.
     * @param {any} p
     */
    const toApp = (p) => {
        const message = { runId: p.runId, recipe: p.recipe, project: p.project, step: p.step, line: p.line, done: p.done };
        Promise.resolve(o.executeCommand(PROGRESS_COMMAND, JSON.stringify(message)))
            .then(undefined, (err) => log('progress not delivered: ' + String(err)));
    };

    /**
     * Starts a run and resolves when it ends.
     * @param {string} recipeId
     * @param {string | null | undefined} name
     * @param {(p: any) => void} [onEvent]
     * @param {(runId: string) => void} [onStart]
     */
    function run(recipeId, name, onEvent, onStart) {
        const recipe = recipes.get(String(recipeId));
        if (!recipe) {
            return Promise.reject(new Error('There is no recipe "' + recipeId + '". Run "kit list" to see them.'));
        }
        if (active) return Promise.reject(new Error('Another setup is running. Wait for it to finish, or cancel it.'));
        const runId = engine.newRunId();
        const signal = { cancelled: false };
        active = { runId, signal };
        if (onStart) onStart(runId);
        return engine.runRecipe({
            recipe, name: name || null, runId, signal,
            extensionDir: o.extensionDir, home: o.home, projectsDir: o.projectsDir,
            onProgress: (p) => { toApp(p); if (onEvent) onEvent(p); },
            installToolchain: (pack) => Promise.resolve(o.executeCommand(INSTALL_COMMAND, pack)),
            ...(o.runOptions || {}),
        }).finally(() => { active = null; });
    }

    return {
        recipes,
        problems: loaded.problems,
        list: () => engine.summaries([...recipes.values()]),
        /** @param {any} args @param {(e: unknown) => void} onEvent */
        add: (args, onEvent) => run(args.recipe, args.name, onEvent),
        /**
         * For the app: starts a run and answers its id at once; progress goes
         * to the app only.
         * @param {string} recipeId @param {string} [name]
         */
        start(recipeId, name) {
            return new Promise((resolve) => {
                run(recipeId, name, undefined, resolve).catch((err) => {
                    // Refused before it began (unknown recipe, another run):
                    // the app hears it as a run that ended at once, since a
                    // command it sends through the bridge has no answer.
                    const runId = engine.newRunId();
                    const project = require('path').join(o.projectsDir, name && !engine.nameProblem(name) ? name : String(recipeId));
                    toApp({
                        runId, recipe: String(recipeId), project,
                        step: { index: 0, total: 0, title: '', state: 'fail' }, line: null,
                        done: { ok: false, projectPath: project, message: err instanceof Error ? err.message : String(err) },
                    });
                    resolve(runId);
                });
            });
        },
        /** @param {any} args */
        cancel(args) {
            const runId = args && typeof args === 'object' ? args.runId : args;
            if (!active || (runId && runId !== active.runId)) return false;
            active.signal.cancelled = true;
            if (active.signal.onCancel) active.signal.onCancel();
            return true;
        },
        /** @param {any} args */
        async remove(args) {
            const pack = engine.packId(args && args.pack);
            if (!pack) throw new Error('Unknown pack "' + (args && args.pack) + '". Packs: ruby, java, cpp.');
            if (!engine.installedPacks(o.home).has(pack)) {
                return engine.PACKS[/** @type {keyof engine.PACKS} */ (pack)].label + ' is not installed.';
            }
            try {
                await o.executeCommand(REMOVE_COMMAND, pack);
            } catch {
                throw new Error('The app could not be asked to remove it. Remove it from Toolchains instead.');
            }
            return 'Removing ' + engine.PACKS[/** @type {keyof engine.PACKS} */ (pack)].label + '.';
        },
        /** The app's health checks, or null when the app does not answer. */
        async health() {
            try {
                const answer = await Promise.race([
                    Promise.resolve(o.executeCommand(HEALTH_COMMAND)),
                    new Promise((_, reject) => setTimeout(() => reject(new Error('timeout')), HEALTH_TIMEOUT_MS).unref()),
                ]);
                const parsed = typeof answer === 'string' ? JSON.parse(answer) : answer;
                return parsed && Array.isArray(parsed.checks) ? parsed : null;
            } catch {
                return null;
            }
        },
        get activeRunId() { return active ? active.runId : null; },
    };
}

module.exports = { createService, PROGRESS_COMMAND, HEALTH_COMMAND, INSTALL_COMMAND, REMOVE_COMMAND };
