// @ts-check
'use strict';

/**
 * Plain-language errors: applies the catalog `errors.json` (beside this file)
 * to log text and says what went wrong and what fixes it.
 *
 * Used by `kit explain`, the editor's "Explain this error", and mirrored in
 * Kotlin (diligent/run/ErrorCatalog.kt) for the Run screen, which reads the
 * same errors.json from the app's assets. The rules, kept identical on both
 * sides:
 *
 * - An entry matches when any of its `patterns` matches, or the run ended
 *   with one of its `exitCodes`, and every pattern in `with` matches too,
 *   anywhere in the text.
 * - Patterns are regular expressions applied with `^` and `$` at line ends,
 *   case-sensitive unless the entry says `ignoreCase`.
 * - `{{1}}` is group 1 of the first pattern that matched. `location` patterns
 *   (group 1 the file, group 2 the line) are tried in order on the lines
 *   around that match; the first that matches gives `{{file}}` and
 *   `{{line}}`, and `explanationAt` replaces `explanation`. Without a
 *   location, an `openFileAtLine` fix is dropped.
 * - Matches come back in catalog order. Only the last MAX_TEXT characters of
 *   the text are read.
 */

const fs = require('fs');
const path = require('path');

const MAX_TEXT = 256 * 1024;
/** Lines before and after the matched line that a location is looked for in. */
const LOCATION_WINDOW = 6;

/**
 * @typedef {{ action: string, label: string, recipe?: string, file?: string, line?: number }} Fix
 * @typedef {{ id: string, title: string, explanation: string, fixes: Fix[], file?: string, line?: number }} Explanation
 */

/** @param {string} [dir] */
function loadCatalog(dir = __dirname) {
    return JSON.parse(fs.readFileSync(path.join(dir, 'errors.json'), 'utf8'));
}

/** @param {string} source @param {boolean} ignoreCase */
function regex(source, ignoreCase) {
    return new RegExp(source, ignoreCase ? 'im' : 'm');
}

/** @param {string} text @param {Record<string, string>} values */
function fill(text, values) {
    return text.replace(/\{\{(\w+)\}\}/g, (_, key) => (key in values ? values[key] : ''));
}

/**
 * The file and line a location pattern finds near `index` in `text`.
 * @param {string} text @param {number} index @param {string[]} patterns @param {boolean} ignoreCase
 * @returns {{ file: string, line: number } | null}
 */
function locate(text, index, patterns, ignoreCase) {
    const lines = text.split('\n');
    const at = text.slice(0, index).split('\n').length - 1;
    const near = lines.slice(Math.max(0, at - LOCATION_WINDOW), at + LOCATION_WINDOW + 1).join('\n');
    for (const p of patterns) {
        const m = regex(p, ignoreCase).exec(near);
        if (m && m[1] && m[2]) return { file: m[1], line: Number(m[2]) };
    }
    return null;
}

/**
 * Every catalog entry that `text` (and the exit code) matches.
 * @param {string} text
 * @param {{ exitCode?: number | null, catalog?: any }} [options]
 * @returns {Explanation[]}
 */
function explain(text, options = {}) {
    const catalog = options.catalog || loadCatalog();
    const body = String(text || '').slice(-MAX_TEXT).replace(/\r\n?/g, '\n');
    const code = options.exitCode;
    /** @type {Explanation[]} */
    const out = [];
    for (const e of catalog.errors) {
        const ignoreCase = Boolean(e.ignoreCase);
        /** @type {RegExpExecArray | null} */
        let hit = null;
        for (const p of e.patterns || []) {
            hit = regex(p, ignoreCase).exec(body);
            if (hit) break;
        }
        const byCode = typeof code === 'number' && Array.isArray(e.exitCodes) && e.exitCodes.includes(code);
        if (!hit && !byCode) continue;
        if (!(e.with || []).every((/** @type {string} */ p) => regex(p, ignoreCase).test(body))) continue;
        const where = hit && e.location ? locate(body, hit.index, e.location, ignoreCase) : null;
        /** @type {Record<string, string>} */
        const values = { 1: hit && hit[1] ? hit[1] : '' };
        if (where) { values.file = where.file; values.line = String(where.line); }
        const fixes = (e.fixes || [])
            .filter((/** @type {any} */ f) => f.action !== 'openFileAtLine' || where)
            .map((/** @type {any} */ f) => ({ ...f, ...(f.action === 'openFileAtLine' && where ? where : {}) }));
        /** @type {Explanation} */
        const result = {
            id: e.id,
            title: fill(e.title, values),
            explanation: fill(where && e.explanationAt ? e.explanationAt : e.explanation, values),
            fixes,
        };
        if (where) { result.file = where.file; result.line = where.line; }
        out.push(result);
    }
    return out;
}

/**
 * What `kit explain` prints.
 * @param {Explanation[]} found
 */
function formatExplanations(found) {
    if (found.length === 0) return 'No known error found in that text. Copy the lines around the first error and try again.';
    const lines = [];
    for (const f of found) {
        if (lines.length) lines.push('');
        lines.push(f.title);
        lines.push('  ' + f.explanation);
        for (const fix of f.fixes) lines.push('  Fix: ' + fix.label + (fix.recipe ? ' (kit add ' + fix.recipe + ')' : ''));
    }
    return lines.join('\n');
}

module.exports = { MAX_TEXT, loadCatalog, explain, formatExplanations };
