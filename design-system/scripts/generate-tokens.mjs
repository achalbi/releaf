#!/usr/bin/env node
/*
 * generate-tokens.mjs
 *
 * Reads design-system/design-tokens.json and emits AppColors into the two
 * platform trees directly — there is no intermediate "generated" holding area,
 * because Swift + Gradle only compile what lives under the target's source
 * root:
 *   - ios/Releaf/DesignSystem/AppColors.generated.swift
 *   - android/app/src/main/java/app/releaf/mobile/ui/theme/AppColors.generated.kt
 *
 * Ramps (color.scale.*.<stop>) emit flat, appearance-agnostic members.
 * Role tokens (surface/text/border/accent/semantic/action/pattern) emit
 * theme-aware members using each platform's dynamic-color primitive:
 *   - iOS:     Color(UIColor { trait in ... }) via dynamicColor() helper
 *   - Android: @Composable @ReadOnlyComposable get() = if (isSystemInDarkTheme()) ...
 *
 * Invocation:
 *   node design-system/scripts/generate-tokens.mjs
 *   node design-system/scripts/generate-tokens.mjs --out /tmp/scratch
 *     (writes a mirror tree under <scratch>/{ios,android}/... so the CI parity
 *     check can diff against the real platform paths.)
 *
 * See docs/TOKEN_PIPELINE.md for the full design rationale.
 */

import { readFileSync, writeFileSync, mkdirSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, resolve, join } from 'node:path';

// ---------- Paths ----------

const __dirname = dirname(fileURLToPath(import.meta.url));
const REPO_ROOT = resolve(__dirname, '..', '..');
const INPUT = join(REPO_ROOT, 'design-system', 'design-tokens.json');

// Relative paths under the destination root where the platform files land.
// Shared between the real write and the --out scratch mode so both paths are
// computed the same way — the CI check diffs by overlaying the scratch root.
const IOS_REL     = ['ios', 'Releaf', 'DesignSystem', 'AppColors.generated.swift'];
const ANDROID_REL = ['android', 'app', 'src', 'main', 'java', 'app', 'releaf', 'mobile', 'ui', 'theme', 'AppColors.generated.kt'];

function parseRoot(argv) {
    const idx = argv.indexOf('--out');
    if (idx >= 0 && argv[idx + 1]) {
        return resolve(argv[idx + 1]);
    }
    return REPO_ROOT;
}

const ROOT = parseRoot(process.argv.slice(2));
const IOS_OUT     = join(ROOT, ...IOS_REL);
const ANDROID_OUT = join(ROOT, ...ANDROID_REL);

// ---------- Token inventory ----------

/**
 * The ramps to emit. Each stop is emitted as `<prefix><stop>` on its platform.
 * Flat values — no theming — read straight from color.scale.<name>.<stop>.
 */
const RAMPS = [
    { jsonKey: 'neutral', stops: ['50','100','200','300','400','500','600','700','800','900','950'], swiftPrefix: 'neutral', kotlinPrefix: 'Neutral' },
    { jsonKey: 'coral',   stops: ['50','100','500','700'],                                           swiftPrefix: 'coral',   kotlinPrefix: 'Coral' },
    { jsonKey: 'success', stops: ['50','100','600','700'],                                           swiftPrefix: 'success', kotlinPrefix: 'Success' },
    { jsonKey: 'info',    stops: ['50','100','600','700'],                                           swiftPrefix: 'info',    kotlinPrefix: 'Info' },
    { jsonKey: 'warning', stops: ['50','100','600','700'],                                           swiftPrefix: 'warning', kotlinPrefix: 'Warning' },
    { jsonKey: 'danger',  stops: ['50','100','600','700'],                                           swiftPrefix: 'danger',  kotlinPrefix: 'Danger' },
];

/**
 * Role tokens that become theme-aware. Explicit manifest — each entry is one
 * line of config, easy to diff-review when role assignments change.
 *
 * `path` is the JSON path under `color.*`.
 * `swift`  / `kotlin` are the emitted member names.
 */
const ROLE_TOKENS = [
    // Surface
    { path: ['surface', 'canvas'],    swift: 'canvas',    kotlin: 'Canvas' },
    { path: ['surface', 'card'],      swift: 'card',      kotlin: 'Card' },
    { path: ['surface', 'cardSolid'], swift: 'cardSolid', kotlin: 'CardSolid' },
    { path: ['surface', 'subtle'],    swift: 'subtle',    kotlin: 'Subtle' },
    { path: ['surface', 'muted'],     swift: 'muted',     kotlin: 'Muted' },
    { path: ['surface', 'inputBg'],   swift: 'inputBg',   kotlin: 'InputBg' },

    // Text
    { path: ['text', 'primary'],   swift: 'textPrimary',   kotlin: 'TextPrimary' },
    { path: ['text', 'secondary'], swift: 'textSecondary', kotlin: 'TextSecondary' },
    { path: ['text', 'tertiary'],  swift: 'textTertiary',  kotlin: 'TextTertiary' },
    { path: ['text', 'onAccent'],  swift: 'onAccent',      kotlin: 'OnAccent' },
    // Historical alias — older call sites use `textOnAccent`. Emit both.
    { path: ['text', 'onAccent'],  swift: 'textOnAccent',  kotlin: 'TextOnAccent' },

    // Border
    { path: ['border', 'default'], swift: 'borderDefault', kotlin: 'BorderDefault' },
    { path: ['border', 'strong'],  swift: 'borderStrong',  kotlin: 'BorderStrong' },

    // Accent
    { path: ['accent', 'coral'],        swift: 'coral',        kotlin: 'Coral' },
    { path: ['accent', 'coralSoft'],    swift: 'coralSoft',    kotlin: 'CoralSoft' },
    { path: ['accent', 'coralDeep'],    swift: 'coralDeep',    kotlin: 'CoralDeep' },
    { path: ['accent', 'coralOutline'], swift: 'coralOutline', kotlin: 'CoralOutline' },
    { path: ['accent', 'green'],        swift: 'green',        kotlin: 'Green' },
    { path: ['accent', 'greenSoft'],    swift: 'greenSoft',    kotlin: 'GreenSoft' },
    { path: ['accent', 'greenText'],    swift: 'greenText',    kotlin: 'GreenText' },

    // Semantic
    { path: ['semantic', 'success'],     swift: 'success',     kotlin: 'Success' },
    { path: ['semantic', 'successSoft'], swift: 'successSoft', kotlin: 'SuccessSoft' },
    { path: ['semantic', 'info'],        swift: 'info',        kotlin: 'Info' },
    { path: ['semantic', 'infoSoft'],    swift: 'infoSoft',    kotlin: 'InfoSoft' },
    { path: ['semantic', 'warning'],     swift: 'warning',     kotlin: 'Warning' },
    { path: ['semantic', 'warningSoft'], swift: 'warningSoft', kotlin: 'WarningSoft' },
    { path: ['semantic', 'neutral'],     swift: 'neutral',     kotlin: 'Neutral' },
    { path: ['semantic', 'neutralSoft'], swift: 'neutralSoft', kotlin: 'NeutralSoft' },
    { path: ['semantic', 'danger'],      swift: 'danger',      kotlin: 'Danger' },

    // Action
    { path: ['action', 'primary'],        swift: 'actionPrimary',        kotlin: 'ActionPrimary' },
    { path: ['action', 'primaryPressed'], swift: 'actionPrimaryPressed', kotlin: 'ActionPrimaryPressed' },
    { path: ['action', 'onPrimary'],      swift: 'onPrimary',            kotlin: 'OnPrimary' },

    // Pattern
    { path: ['pattern', 'dotGrid'], swift: 'dotGrid', kotlin: 'DotGrid' },
];

// ---------- Color parsing ----------

/** Parse a #RRGGBB or #RRGGBBAA hex string → { rgb: 0xRRGGBB, alpha: 0..1 }. */
function parseHex(str) {
    const s = String(str).replace(/^#/, '');
    if (!/^[0-9A-Fa-f]+$/.test(s)) throw new Error(`Invalid hex characters: ${str}`);
    if (s.length === 6) {
        return { rgb: parseInt(s, 16), alpha: 1.0 };
    }
    if (s.length === 8) {
        const rgb = parseInt(s.substring(0, 6), 16);
        const alpha = parseInt(s.substring(6, 8), 16) / 255;
        return { rgb, alpha };
    }
    throw new Error(`Hex must be 6 or 8 chars (got ${s.length}): ${str}`);
}

function hex6(rgb) {
    return rgb.toString(16).toUpperCase().padStart(6, '0');
}

function hex2(byte) {
    return byte.toString(16).toUpperCase().padStart(2, '0');
}

/** Produce an 8-digit Kotlin color literal: 0xAARRGGBB. */
function kotlinArgb(rgb, alpha) {
    const a = Math.round(Math.max(0, Math.min(1, alpha)) * 255);
    return `0x${hex2(a)}${hex6(rgb)}`;
}

/** Swift alpha literal with 4 sig figs to avoid byte-rounding drift. */
function swiftAlpha(alpha) {
    return alpha.toFixed(4).replace(/0+$/, '').replace(/\.$/, '.0');
}

// ---------- Lookups ----------

function rampStop(tokens, rampKey, stop) {
    const node = tokens.color.scale[rampKey]?.[stop];
    if (!node) throw new Error(`Missing ramp stop color.scale.${rampKey}.${stop}`);
    return parseHex(node.value);
}

function roleValues(tokens, path) {
    let node = tokens.color;
    for (const seg of path) {
        node = node?.[seg];
        if (!node) throw new Error(`Missing role token color.${path.join('.')}`);
    }
    const v = node.value;
    if (!v || typeof v !== 'object' || !('light' in v) || !('dark' in v)) {
        throw new Error(`Role token color.${path.join('.')} must carry { light, dark } values`);
    }
    return {
        light: parseHex(v.light),
        dark:  parseHex(v.dark),
        description: node.description || null,
    };
}

// ---------- Swift emitter ----------

function emitSwift(tokens) {
    const lines = [];
    const p = (s) => lines.push(s);

    p('// GENERATED — DO NOT EDIT.');
    p('// Run `node design-system/scripts/generate-tokens.mjs` to regenerate.');
    p('//');
    p('// Source: design-system/design-tokens.json');
    p('');
    p('import SwiftUI');
    p('import UIKit');
    p('');
    p('public enum AppColors {');
    p('');
    p('    // MARK: - Ramps (appearance-agnostic)');

    for (const ramp of RAMPS) {
        p('');
        p(`    // ${ramp.jsonKey} — ${ramp.stops.length} stops`);
        for (const stop of ramp.stops) {
            const { rgb, alpha } = rampStop(tokens, ramp.jsonKey, stop);
            const name = `${ramp.swiftPrefix}${stop}`;
            if (alpha === 1.0) {
                p(`    public static let ${name} = Color(hex: 0x${hex6(rgb)})`);
            } else {
                p(`    public static let ${name} = Color(hex: 0x${hex6(rgb)}, alpha: ${swiftAlpha(alpha)})`);
            }
        }
    }

    p('');
    p('    // MARK: - Roles (theme-aware via dynamicColor(…))');

    // Dedup by JSON path (not emitted name) so two ROLE_TOKENS entries pointing
    // at the same role — e.g. `onAccent` + `textOnAccent` — produce one primary
    // and one alias, never two primaries. Mirrors the Kotlin emitter below.
    const pathKey = (path) => path.join('.');
    const emittedPrimary = new Map(); // pathKey → first swift name
    for (const role of ROLE_TOKENS) {
        const k = pathKey(role.path);
        if (emittedPrimary.has(k)) continue;
        emittedPrimary.set(k, role.swift);
        const vals = roleValues(tokens, role.path);
        p('');
        if (vals.description) p(`    /// ${vals.description}`);
        p(`    public static let ${role.swift} = dynamicColor(`);
        const args = [
            `light: 0x${hex6(vals.light.rgb)}`,
            vals.light.alpha !== 1.0 ? `lightAlpha: ${swiftAlpha(vals.light.alpha)}` : null,
            `dark: 0x${hex6(vals.dark.rgb)}`,
            vals.dark.alpha !== 1.0 ? `darkAlpha: ${swiftAlpha(vals.dark.alpha)}` : null,
        ].filter(Boolean);
        // Indent each argument line.
        for (let i = 0; i < args.length; i++) {
            const tail = i === args.length - 1 ? '' : ',';
            p(`        ${args[i]}${tail}`);
        }
        p(`    )`);
    }

    // Aliases — additional entries sharing a path. Emitted as `= <primary>`
    // so there's no double dynamic-provider allocation.
    const aliases = [];
    for (const role of ROLE_TOKENS) {
        const k = pathKey(role.path);
        const primary = emittedPrimary.get(k);
        if (primary !== role.swift) aliases.push({ name: role.swift, primary });
    }
    if (aliases.length) {
        p('');
        p('    // MARK: - Aliases (historical names for the same role)');
        for (const a of aliases) {
            p('');
            p(`    /// Alias of \`${a.primary}\``);
            p(`    public static let ${a.name} = ${a.primary}`);
        }
    }

    p('}');
    p('');
    p('// MARK: - Helpers');
    p('');
    p('private func dynamicColor(');
    p('    light: UInt32, lightAlpha: CGFloat = 1,');
    p('    dark: UInt32,  darkAlpha:  CGFloat = 1');
    p(') -> Color {');
    p('    Color(UIColor { trait in');
    p('        trait.userInterfaceStyle == .dark');
    p('            ? UIColor(rgb: dark, alpha: darkAlpha)');
    p('            : UIColor(rgb: light, alpha: lightAlpha)');
    p('    })');
    p('}');
    p('');
    p('private extension UIColor {');
    p('    convenience init(rgb: UInt32, alpha: CGFloat = 1) {');
    p('        let r = CGFloat((rgb >> 16) & 0xFF) / 255');
    p('        let g = CGFloat((rgb >>  8) & 0xFF) / 255');
    p('        let b = CGFloat( rgb        & 0xFF) / 255');
    p('        self.init(red: r, green: g, blue: b, alpha: alpha)');
    p('    }');
    p('}');
    p('');
    p('public extension Color {');
    p('    /// `Color(hex: 0xE77850)` — read hex literals the same way you write them.');
    p('    init(hex: UInt32, alpha: Double = 1.0) {');
    p('        let r = Double((hex >> 16) & 0xFF) / 255.0');
    p('        let g = Double((hex >>  8) & 0xFF) / 255.0');
    p('        let b = Double( hex        & 0xFF) / 255.0');
    p('        self.init(.sRGB, red: r, green: g, blue: b, opacity: alpha)');
    p('    }');
    p('}');

    return lines.join('\n') + '\n';
}

// ---------- Kotlin emitter ----------

function emitKotlin(tokens) {
    const lines = [];
    const p = (s) => lines.push(s);

    p('// GENERATED — DO NOT EDIT.');
    p('// Run `node design-system/scripts/generate-tokens.mjs` to regenerate.');
    p('//');
    p('// Source: design-system/design-tokens.json');
    p('');
    p('package app.releaf.mobile.ui.theme');
    p('');
    p('import androidx.compose.foundation.isSystemInDarkTheme');
    p('import androidx.compose.runtime.Composable');
    p('import androidx.compose.runtime.ReadOnlyComposable');
    p('import androidx.compose.ui.graphics.Color');
    p('');
    p('object AppColors {');

    // Ramps (flat `val`s — computed at class load).
    for (const ramp of RAMPS) {
        p('');
        p(`    // ${ramp.jsonKey} — ${ramp.stops.length} stops`);
        for (const stop of ramp.stops) {
            const { rgb, alpha } = rampStop(tokens, ramp.jsonKey, stop);
            const name = `${ramp.kotlinPrefix}${stop}`;
            p(`    val ${name} = Color(${kotlinArgb(rgb, alpha)})`);
        }
    }

    // Roles (theme-aware @Composable getters).
    p('');
    p('    // Roles (theme-aware — resolve per recomposition via isSystemInDarkTheme())');

    // Track primary name per path for aliases (same shape as Swift).
    const pathKey = (path) => path.join('.');
    const emittedPrimary = new Map(); // pathKey → kotlin name
    for (const role of ROLE_TOKENS) {
        const k = pathKey(role.path);
        if (!emittedPrimary.has(k)) {
            emittedPrimary.set(k, role.kotlin);
            const vals = roleValues(tokens, role.path);
            p('');
            if (vals.description) p(`    /** ${vals.description} */`);
            p(`    val ${role.kotlin}: Color`);
            p(`        @Composable @ReadOnlyComposable`);
            p(`        get() = if (isSystemInDarkTheme()) Color(${kotlinArgb(vals.dark.rgb, vals.dark.alpha)}) else Color(${kotlinArgb(vals.light.rgb, vals.light.alpha)})`);
        }
    }

    // Aliases.
    const aliasLines = [];
    for (const role of ROLE_TOKENS) {
        const k = pathKey(role.path);
        const primary = emittedPrimary.get(k);
        if (primary !== role.kotlin) {
            aliasLines.push({ name: role.kotlin, primary });
        }
    }
    if (aliasLines.length) {
        p('');
        p('    // Aliases — older call sites use different names for the same role.');
        for (const a of aliasLines) {
            p('');
            p(`    /** Alias of [${a.primary}] */`);
            p(`    val ${a.name}: Color`);
            p(`        @Composable @ReadOnlyComposable`);
            p(`        get() = ${a.primary}`);
        }
    }

    p('}');

    return lines.join('\n') + '\n';
}

// ---------- Drive ----------

function main() {
    const tokens = JSON.parse(readFileSync(INPUT, 'utf8'));

    mkdirSync(dirname(IOS_OUT),     { recursive: true });
    mkdirSync(dirname(ANDROID_OUT), { recursive: true });

    writeFileSync(IOS_OUT,     emitSwift(tokens));
    writeFileSync(ANDROID_OUT, emitKotlin(tokens));

    console.log(`Wrote ${IOS_OUT}`);
    console.log(`Wrote ${ANDROID_OUT}`);
}

main();
