// @ts-check
'use strict';

/**
 * Snapshots: the project's files as Git trees, chained on the ref
 * `refs/diligent/snapshots`, never on the user's branch.
 *
 * The recipe (AGENT_GUIDE.md, Phase 2):
 *
 *     export GIT_INDEX_FILE=.git/diligent-index
 *     git add -A && tree=$(git write-tree)
 *     commit=$(git commit-tree "$tree" -p refs/diligent/snapshots -m "diligent: <reason>")
 *     git update-ref refs/diligent/snapshots "$commit"
 *
 * A private index keeps the user's staging area as it is, `add -A` follows
 * `.gitignore`, and nothing touches HEAD, the branch or the files. Restoring is
 * the one step that writes files: a "before restore" snapshot first, then
 * `git checkout <commit> -- .`, also through the private index so what the
 * user had staged stays staged.
 *
 * Every Git call is an argv array for execFile, never a shell string, so a
 * reason or a path is data whatever it contains. The `git` function every step
 * takes is `(args, {env}) => Promise<{code, stdout, stderr}>`, which lets
 * scripts/test-diligent-core.js run the steps against a real Git in a
 * temporary folder.
 */

const path = require('path');

const REF = 'refs/diligent/snapshots';
const INDEX_NAME = 'diligent-index';
const MESSAGE_PREFIX = 'diligent: ';

/** Keep this many snapshots. The chain is cut back to it once it grows past KEEP + PRUNE_SLACK. */
const KEEP = 100;
const PRUNE_SLACK = 20;

/** Snapshot every this often, while files were saved since the last one. */
const INTERVAL_MS = 10 * 60 * 1000;

/**
 * Snapshot commits are Diligent's, so they carry its name rather than
 * requiring the user to have set one: commit-tree refuses to run without an
 * identity, and a phone's Git often has none.
 */
const IDENTITY = Object.freeze({
    GIT_AUTHOR_NAME: 'Diligent',
    GIT_AUTHOR_EMAIL: 'diligent@localhost',
    GIT_COMMITTER_NAME: 'Diligent',
    GIT_COMMITTER_EMAIL: 'diligent@localhost',
});

/**
 * A reason as one line of a commit message.
 * @param {unknown} reason
 */
function cleanReason(reason) {
    const text = typeof reason === 'string' ? reason.replace(/\s+/g, ' ').trim() : '';
    return (text || 'snapshot').slice(0, 200);
}

/** @param {string} gitDir */
function indexEnv(gitDir) {
    return { GIT_INDEX_FILE: path.join(gitDir, INDEX_NAME) };
}

/**
 * The Git steps of one snapshot, as argv arrays.
 * @param {string} tree
 * @param {string | null} parent
 * @param {string} reason
 */
function commitArgs(tree, parent, reason) {
    const args = ['commit-tree', tree];
    if (parent) args.push('-p', parent);
    args.push('-m', MESSAGE_PREFIX + cleanReason(reason));
    return args;
}

/** @param {string} commit @param {string | null} old */
function updateRefArgs(commit, old) {
    // The old value makes the update refuse if another snapshot moved the ref meanwhile.
    return old ? ['update-ref', REF, commit, old] : ['update-ref', REF, commit, ''];
}

/** `git log` of the chain: commit, time and subject, tab separated. */
function listArgs(limit = KEEP) {
    return ['log', '--format=%H%x09%ct%x09%s', '-n', String(limit), REF, '--'];
}

/**
 * @param {string} text output of listArgs
 * @returns {Array<{commit: string, time: number, reason: string}>} newest first, time in ms
 */
function parseList(text) {
    return text.split('\n').filter(Boolean).map((line) => {
        const [commit, seconds, ...subject] = line.split('\t');
        const s = subject.join('\t');
        return {
            commit,
            time: Number(seconds) * 1000,
            reason: s.startsWith(MESSAGE_PREFIX) ? s.slice(MESSAGE_PREFIX.length) : s,
        };
    }).filter((e) => /^[0-9a-f]{40,64}$/.test(e.commit) && Number.isFinite(e.time));
}

/** `git checkout <commit> -- .`, the restore step. */
function restoreArgs(/** @type {string} */ commit) {
    return ['checkout', commit, '--', '.'];
}

/**
 * @typedef {{code: number, stdout: string, stderr: string}} GitResult
 * @typedef {(args: string[], options?: {env?: Record<string, string>}) => Promise<GitResult>} Git
 */

/**
 * @param {Git} git
 * @param {string[]} args
 * @param {Record<string, string>} [env]
 */
async function must(git, args, env) {
    const r = await git(args, { env });
    if (r.code !== 0) throw new Error('git ' + args[0] + ' failed: ' + (r.stderr || r.stdout).trim());
    return r.stdout.trim();
}

/**
 * The repository's own directory, or null when the folder is not in one.
 * @param {Git} git
 */
async function gitDirOf(git) {
    const r = await git(['rev-parse', '--absolute-git-dir']);
    return r.code === 0 ? r.stdout.trim() : null;
}

/**
 * The newest snapshot, or null when there is none yet.
 * @param {Git} git
 */
async function head(git) {
    const r = await git(['rev-parse', '--verify', '-q', REF + '^{commit}']);
    return r.code === 0 ? r.stdout.trim() : null;
}

/**
 * Takes a snapshot. Answers the new commit, or null when nothing changed since
 * the last one (no empty snapshots pile up while a project sits untouched).
 * @param {Git} git runs in the project folder
 * @param {string} reason
 */
async function take(git, reason) {
    const gitDir = await gitDirOf(git);
    if (!gitDir) throw new Error('not a Git repository');
    const env = indexEnv(gitDir);
    await must(git, ['add', '-A'], env);
    const tree = await must(git, ['write-tree'], env);
    const parent = await head(git);
    if (parent) {
        const parentTree = await must(git, ['rev-parse', parent + '^{tree}']);
        if (parentTree === tree) return null;
    }
    const commit = await must(git, commitArgs(tree, parent, reason), IDENTITY);
    await must(git, updateRefArgs(commit, parent));
    await prune(git);
    return commit;
}

/**
 * Cuts the chain back to the newest KEEP snapshots once it is longer than
 * KEEP + PRUNE_SLACK: the newest KEEP are written again, oldest first, on a
 * chain that starts at the first of them, with their own dates and messages.
 * The old commits are then unreachable and Git's own gc removes them.
 * @param {Git} git
 * @param {number} [keep]
 * @param {number} [slack]
 */
async function prune(git, keep = KEEP, slack = PRUNE_SLACK) {
    const count = Number(await must(git, ['rev-list', '--count', REF]));
    if (!(count > keep + slack)) return false;
    const old = await must(git, ['rev-parse', REF]);
    const lines = (await must(git, ['log', '--format=%T%x09%at%x09%ct%x09%s', '-n', String(keep), REF, '--']))
        .split('\n').filter(Boolean).reverse();
    /** @type {string | null} */
    let parent = null;
    for (const line of lines) {
        const [tree, authored, committed, ...subject] = line.split('\t');
        const args = ['commit-tree', tree];
        if (parent) args.push('-p', parent);
        args.push('-m', subject.join('\t'));
        parent = await must(git, args, {
            ...IDENTITY,
            GIT_AUTHOR_DATE: '@' + authored + ' +0000',
            GIT_COMMITTER_DATE: '@' + committed + ' +0000',
        });
    }
    if (parent) await must(git, ['update-ref', REF, parent, old]);
    return true;
}

/**
 * Up to KEEP snapshots, newest first.
 * @param {Git} git
 */
async function list(git) {
    if (!(await head(git))) return [];
    return parseList(await must(git, listArgs()));
}

/**
 * Puts the files back as they were in `commit`, after a "before restore"
 * snapshot of how they are now. Files created since are left in place.
 * @param {Git} git
 * @param {string} commit
 */
async function restore(git, commit) {
    if (!/^[0-9a-f]{40,64}$/.test(commit)) throw new Error('not a snapshot: ' + commit);
    const gitDir = await gitDirOf(git);
    if (!gitDir) throw new Error('not a Git repository');
    await take(git, 'before restore');
    await must(git, restoreArgs(commit), indexEnv(gitDir));
}

module.exports = {
    REF,
    INDEX_NAME,
    KEEP,
    PRUNE_SLACK,
    INTERVAL_MS,
    IDENTITY,
    cleanReason,
    indexEnv,
    commitArgs,
    updateRefArgs,
    listArgs,
    parseList,
    restoreArgs,
    gitDirOf,
    head,
    take,
    prune,
    list,
    restore,
};
