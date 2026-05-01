#!/usr/bin/env node
/*
 * check-schema-drift.mjs
 *
 * Verifies that tables present in BOTH the Releaf and QuickInk SQLite
 * migrations have identical column definitions, constraints, and
 * indexes. Tables in only one schema are app-specific by design and
 * are ignored.
 *
 * Why this exists: QUICKINK_DESIGN.md §3 picked forked schemas
 * (separate v1_initial.sql per app) for readability, with a CI check
 * to keep shared columns aligned. This is that check.
 *
 * Inputs:
 *   shared/design-system/migrations/v1_initial.sql           ← Releaf
 *   shared/design-system/migrations/quickink/v1_initial.sql  ← QuickInk
 *     (optional — script no-ops if QuickInk schema doesn't exist yet,
 *      so this CI check is safe to land before QuickInk's schema is
 *      written)
 *   shared/design-system/migrations/drift-allowlist.yaml     ← intentional drift
 *
 * Exit codes:
 *   0 — no drift, or QuickInk schema not present yet (nothing to compare)
 *   1 — drift detected; diff printed to stderr
 *   2 — tool failure (parse error, missing required file, etc.)
 *
 * Usage:
 *   node shared/design-system/scripts/check-schema-drift.mjs
 *
 * Implementation notes:
 *   - Hand-rolled SQL parser scoped to the subset of CREATE TABLE / CREATE
 *     INDEX syntax used in v1_initial.sql. SQLite's full grammar is large;
 *     we only need to recognize what the migrations actually use. Any
 *     syntax extension to the migrations means extending the parser.
 *   - Column comparison is order-sensitive (column order matters for
 *     SELECT *, ALTER TABLE compatibility, and Drive payload ordering).
 *   - Allowlist is keyed by table; per-table you can list `releaf_only_columns`
 *     or `quickink_only_columns` plus a required `reason` string. Listing
 *     a column on the wrong side is itself a parse error.
 */

import { readFileSync, existsSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, resolve, join } from 'node:path';

// ─── Paths ─────────────────────────────────────────────────────────────

const __dirname = dirname(fileURLToPath(import.meta.url));
const REPO_ROOT = resolve(__dirname, '..', '..', '..');
const MIGRATIONS_DIR = join(REPO_ROOT, 'shared', 'design-system', 'migrations');

const RELEAF_SCHEMA   = join(MIGRATIONS_DIR, 'v1_initial.sql');
const QUICKINK_SCHEMA = join(MIGRATIONS_DIR, 'quickink', 'v1_initial.sql');
const ALLOWLIST       = join(MIGRATIONS_DIR, 'drift-allowlist.yaml');

// ─── Parser ────────────────────────────────────────────────────────────

/**
 * Strip SQL comments (-- to EOL, /* … *\/ blocks) and normalize whitespace.
 * Keep newlines so we can still report line numbers if we add that later.
 */
function stripComments(sql) {
    return sql
        .replace(/\/\*[\s\S]*?\*\//g, '')   // /* … */ blocks
        .replace(/--[^\n]*/g, '');           // -- to EOL
}

/**
 * Parse `CREATE TABLE name ( col_defs , … );` blocks.
 * Returns: { tableName: { columns: [{name, def}], constraints: [...] } }
 *
 * Column rows and table-level constraints are distinguished by leading
 * keyword: PRIMARY/UNIQUE/CHECK/FOREIGN/CONSTRAINT → constraint, else column.
 */
function parseTables(sql) {
    const stripped = stripComments(sql);
    const tables = {};

    // Match: CREATE TABLE [IF NOT EXISTS] <name> ( <body> )
    // Capture group 1 = table name, group 2 = body between outermost parens.
    // Body may contain commas inside parens (e.g. CHECK (x IN (0,1))) so we
    // do paren-balancing after a coarse regex match.
    const headerRe = /CREATE\s+TABLE\s+(?:IF\s+NOT\s+EXISTS\s+)?["`]?(\w+)["`]?\s*\(/gi;
    let m;
    while ((m = headerRe.exec(stripped))) {
        const name = m[1];
        const bodyStart = headerRe.lastIndex;
        // Walk paren depth from bodyStart to find matching close.
        let depth = 1;
        let i = bodyStart;
        while (i < stripped.length && depth > 0) {
            const ch = stripped[i];
            if (ch === '(') depth++;
            else if (ch === ')') depth--;
            i++;
        }
        if (depth !== 0) {
            throw new Error(`Unbalanced parens in CREATE TABLE ${name}`);
        }
        const body = stripped.slice(bodyStart, i - 1);
        tables[name] = parseTableBody(body);
    }
    return tables;
}

/**
 * Split a CREATE TABLE body on TOP-LEVEL commas only (commas inside
 * nested parens like `CHECK (x IN (0,1))` don't split). Then bucket
 * each row into column or constraint.
 */
function parseTableBody(body) {
    const rows = splitTopLevel(body, ',');
    const columns = [];
    const constraints = [];
    for (const raw of rows) {
        const row = raw.trim();
        if (!row) continue;
        const upper = row.toUpperCase();
        if (
            upper.startsWith('PRIMARY KEY') ||
            upper.startsWith('UNIQUE') ||
            upper.startsWith('CHECK') ||
            upper.startsWith('FOREIGN KEY') ||
            upper.startsWith('CONSTRAINT')
        ) {
            constraints.push(normalize(row));
        } else {
            // Column: first whitespace-delimited token is the name.
            const space = row.search(/\s/);
            if (space === -1) {
                throw new Error(`Cannot parse column row: ${row}`);
            }
            const name = row.slice(0, space).replace(/["`]/g, '');
            const def = normalize(row.slice(space + 1));
            columns.push({ name, def });
        }
    }
    return { columns, constraints };
}

function splitTopLevel(s, sep) {
    const parts = [];
    let depth = 0;
    let start = 0;
    for (let i = 0; i < s.length; i++) {
        const ch = s[i];
        if (ch === '(') depth++;
        else if (ch === ')') depth--;
        else if (ch === sep && depth === 0) {
            parts.push(s.slice(start, i));
            start = i + 1;
        }
    }
    parts.push(s.slice(start));
    return parts;
}

/** Collapse runs of whitespace to single spaces; trim. */
function normalize(s) {
    return s.replace(/\s+/g, ' ').trim();
}

/**
 * Parse `CREATE INDEX [IF NOT EXISTS] [UNIQUE] name ON table (cols) [WHERE …];`
 * Returns: [{ name, table, definition }]
 */
function parseIndexes(sql) {
    const stripped = stripComments(sql);
    const re = /CREATE\s+(UNIQUE\s+)?INDEX\s+(?:IF\s+NOT\s+EXISTS\s+)?["`]?(\w+)["`]?\s+ON\s+["`]?(\w+)["`]?\s*\(([^;]+?)\)\s*(WHERE[^;]+)?;/gi;
    const idxs = [];
    let m;
    while ((m = re.exec(stripped))) {
        idxs.push({
            name: m[2],
            table: m[3],
            unique: !!m[1],
            columns: normalize(m[4]),
            where: m[5] ? normalize(m[5]) : null,
        });
    }
    return idxs;
}

// ─── Allowlist ─────────────────────────────────────────────────────────

/**
 * Minimal YAML reader for our shape:
 *   <table>:
 *     releaf_only_columns:
 *       - col_a
 *       - col_b
 *     quickink_only_columns:
 *       - col_c
 *     reason: "free-form text"
 *
 * We DON'T pull in a yaml dep — keeps the CI step zero-install. If the
 * allowlist file ever needs richer YAML, swap to `yaml` package.
 */
function parseAllowlist(text) {
    const out = {};
    const lines = text.split(/\r?\n/);
    let cur = null;
    let curList = null;
    for (const raw of lines) {
        const line = raw.replace(/#.*$/, '').replace(/\s+$/, '');
        if (!line.trim()) continue;
        const indent = line.match(/^\s*/)[0].length;
        if (indent === 0 && line.endsWith(':')) {
            cur = line.slice(0, -1).trim();
            out[cur] = { releaf_only_columns: [], quickink_only_columns: [], reason: '' };
            curList = null;
        } else if (indent === 2 && line.trim().endsWith(':')) {
            curList = line.trim().slice(0, -1);
        } else if (indent === 2 && line.includes(':')) {
            const [k, v] = line.trim().split(/:\s*/);
            if (k === 'reason') out[cur].reason = v.replace(/^["']|["']$/g, '');
            curList = null;
        } else if (indent === 4 && line.trim().startsWith('- ')) {
            const v = line.trim().slice(2).replace(/^["']|["']$/g, '');
            if (curList === 'releaf_only_columns')   out[cur].releaf_only_columns.push(v);
            if (curList === 'quickink_only_columns') out[cur].quickink_only_columns.push(v);
        }
    }
    // Validate every entry has a non-empty reason.
    for (const [table, entry] of Object.entries(out)) {
        if (!entry.reason || !entry.reason.trim()) {
            throw new Error(`Allowlist entry for "${table}" missing required 'reason:' line`);
        }
    }
    return out;
}

// ─── Diff ──────────────────────────────────────────────────────────────

function diffTables(releaf, quickink, allowlist) {
    const drifts = [];
    const sharedTables = Object.keys(releaf).filter(t => t in quickink);

    for (const table of sharedTables) {
        const r = releaf[table];
        const q = quickink[table];
        const allow = allowlist[table] || { releaf_only_columns: [], quickink_only_columns: [] };

        // Column comparison — order-sensitive after dropping allow-listed cols.
        const rCols = r.columns.filter(c => !allow.releaf_only_columns.includes(c.name));
        const qCols = q.columns.filter(c => !allow.quickink_only_columns.includes(c.name));

        if (rCols.length !== qCols.length) {
            drifts.push({
                table, kind: 'column-count',
                detail: `Releaf has ${rCols.length} non-allowlisted columns, QuickInk has ${qCols.length}`,
                releafColumns:   rCols.map(c => c.name),
                quickinkColumns: qCols.map(c => c.name),
            });
            continue;
        }
        for (let i = 0; i < rCols.length; i++) {
            if (rCols[i].name !== qCols[i].name) {
                drifts.push({
                    table, kind: 'column-name-or-order',
                    detail: `Position ${i}: Releaf=${rCols[i].name}, QuickInk=${qCols[i].name}`,
                });
            } else if (rCols[i].def !== qCols[i].def) {
                drifts.push({
                    table, kind: 'column-definition',
                    detail: `Column ${rCols[i].name}:\n      Releaf:   ${rCols[i].def}\n      QuickInk: ${qCols[i].def}`,
                });
            }
        }

        // Constraint comparison — set comparison (ordering inside CREATE TABLE
        // is not semantically meaningful for table-level constraints).
        const rConstraints = new Set(r.constraints);
        const qConstraints = new Set(q.constraints);
        for (const c of rConstraints) {
            if (!qConstraints.has(c)) {
                drifts.push({
                    table, kind: 'constraint-missing-quickink',
                    detail: `Releaf has constraint not in QuickInk: ${c}`,
                });
            }
        }
        for (const c of qConstraints) {
            if (!rConstraints.has(c)) {
                drifts.push({
                    table, kind: 'constraint-missing-releaf',
                    detail: `QuickInk has constraint not in Releaf: ${c}`,
                });
            }
        }
    }

    return drifts;
}

function diffIndexes(releafIdxs, quickinkIdxs, sharedTables) {
    const drifts = [];
    const releafByTable = group(releafIdxs.filter(i => sharedTables.has(i.table)), 'table');
    const quickinkByTable = group(quickinkIdxs.filter(i => sharedTables.has(i.table)), 'table');

    for (const table of sharedTables) {
        const rIdxs = releafByTable[table] || [];
        const qIdxs = quickinkByTable[table] || [];
        const rByName = Object.fromEntries(rIdxs.map(i => [i.name, i]));
        const qByName = Object.fromEntries(qIdxs.map(i => [i.name, i]));
        const allNames = new Set([...Object.keys(rByName), ...Object.keys(qByName)]);
        for (const name of allNames) {
            const r = rByName[name];
            const q = qByName[name];
            if (!r) {
                drifts.push({
                    table, kind: 'index-missing-releaf',
                    detail: `QuickInk index "${name}" has no Releaf counterpart`,
                });
                continue;
            }
            if (!q) {
                drifts.push({
                    table, kind: 'index-missing-quickink',
                    detail: `Releaf index "${name}" has no QuickInk counterpart`,
                });
                continue;
            }
            if (r.unique !== q.unique || r.columns !== q.columns || r.where !== q.where) {
                drifts.push({
                    table, kind: 'index-definition',
                    detail: `Index ${name} differs:\n      Releaf:   UNIQUE=${r.unique} cols=(${r.columns}) where=${r.where}\n      QuickInk: UNIQUE=${q.unique} cols=(${q.columns}) where=${q.where}`,
                });
            }
        }
    }
    return drifts;
}

function group(arr, key) {
    const out = {};
    for (const x of arr) {
        (out[x[key]] = out[x[key]] || []).push(x);
    }
    return out;
}

// ─── Main ──────────────────────────────────────────────────────────────

function fail(msg, code = 2) {
    console.error(`check-schema-drift.mjs: ${msg}`);
    process.exit(code);
}

function main() {
    if (!existsSync(RELEAF_SCHEMA)) {
        fail(`missing Releaf schema at ${RELEAF_SCHEMA}`);
    }
    if (!existsSync(QUICKINK_SCHEMA)) {
        // No QuickInk schema yet — this CI check is safe to land before
        // the QuickInk schema is written. Exit clean.
        console.log('check-schema-drift.mjs: QuickInk schema not present yet — nothing to compare. OK.');
        process.exit(0);
    }

    let allowlist = {};
    if (existsSync(ALLOWLIST)) {
        try {
            allowlist = parseAllowlist(readFileSync(ALLOWLIST, 'utf8'));
        } catch (e) {
            fail(`allowlist parse error: ${e.message}`);
        }
    }

    let releafTables, quickinkTables, releafIdxs, quickinkIdxs;
    try {
        const releafSrc   = readFileSync(RELEAF_SCHEMA, 'utf8');
        const quickinkSrc = readFileSync(QUICKINK_SCHEMA, 'utf8');
        releafTables   = parseTables(releafSrc);
        quickinkTables = parseTables(quickinkSrc);
        releafIdxs     = parseIndexes(releafSrc);
        quickinkIdxs   = parseIndexes(quickinkSrc);
    } catch (e) {
        fail(`parse error: ${e.message}`);
    }

    const tableDrifts = diffTables(releafTables, quickinkTables, allowlist);

    const sharedTables = new Set(
        Object.keys(releafTables).filter(t => t in quickinkTables)
    );
    const indexDrifts = diffIndexes(releafIdxs, quickinkIdxs, sharedTables);

    const drifts = [...tableDrifts, ...indexDrifts];

    if (drifts.length === 0) {
        const sharedCount = sharedTables.size;
        console.log(`check-schema-drift.mjs: no drift across ${sharedCount} shared table(s). OK.`);
        process.exit(0);
    }

    console.error('Schema drift detected in shared tables:\n');
    for (const d of drifts) {
        console.error(`  [${d.kind}] table "${d.table}":`);
        console.error(`    ${d.detail.replace(/\n/g, '\n    ')}`);
        console.error('');
    }
    console.error(
        `Either align the schemas, or add an entry to ${ALLOWLIST}\n` +
        `with a 'reason:' explaining why the divergence is intentional.\n`
    );
    process.exit(1);
}

main();
