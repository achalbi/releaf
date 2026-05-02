#!/usr/bin/env node
import { execFileSync } from "node:child_process";
import { copyFileSync, mkdirSync, writeFileSync } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const projectRoot = path.resolve(__dirname, "../..");

const colors = {
  canvas: "#FAF7F2",
  coral: "#D97757",
  ink: "#2C2826",
  border: "#EDE4D2",
  white: "#FFFFFF",
  muted: "#7a6f60",
};

const qMark84 = {
  ring: "M59.2 35.7 C57.4 26.2 49.1 19.1 39.2 18.8 C28.2 18.5 19.2 26.8 18.9 38.1 C18.6 50.1 27.4 59.7 39.4 60.1 C50.1 60.4 58.7 52.6 59.8 42.4",
  tail: "M51.6 54.7 C55.2 58.7 59.5 62.5 65.0 65.3 C67.4 66.5 69.1 67.8 70.5 69.4",
  drop: { cx: 72.1, cy: 71.2, r: 3.2 },
};

const qMarkStroke = 5.4;

const iconStroke = {
  color: colors.ink,
  width: 1.7,
  attrs: `fill="none" stroke="${colors.ink}" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"`,
};

const uiIcons = {
  scan: [
    `<path d="M4.3 7.7 V5.7 C4.3 4.9 4.9 4.3 5.7 4.3 H7.8" />`,
    `<path d="M16.2 4.3 H18.3 C19.1 4.3 19.7 4.9 19.7 5.7 V7.7" />`,
    `<path d="M19.7 16.3 V18.3 C19.7 19.1 19.1 19.7 18.3 19.7 H16.2" />`,
    `<path d="M7.8 19.7 H5.7 C4.9 19.7 4.3 19.1 4.3 18.3 V16.3" />`,
    `<path d="M8.4 8.1 H14.5 C15.1 8.1 15.6 8.6 15.6 9.2 V16.1 H8.4 Z" />`,
    `<path d="M10.2 11 H13.7" />`,
    `<path d="M10.2 13.6 H13.1" />`,
  ],
  note: [
    `<path d="M6.3 3.6 H14.2 L18.1 7.5 V20.4 H6.3 Z" />`,
    `<path d="M14.2 3.6 V7.5 H18.1" />`,
    `<path d="M8.8 10.7 H15.2" />`,
    `<path d="M8.8 13.6 H15.2" />`,
    `<path d="M8.8 16.5 H12.8" />`,
  ],
  search: [
    `<circle cx="10.4" cy="10.4" r="5.7" />`,
    `<path d="M14.5 14.5 L19.2 19.2" />`,
    `<path d="M8.3 9.2 H12.7" />`,
    `<path d="M8.3 11.6 H11.3" />`,
  ],
  sync: [
    `<path d="M7.3 18 H16.4 C18.8 18 20.4 16.5 20.4 14.4 C20.4 12.6 19.1 11.1 17.2 10.9 C16.5 8 14.3 6 11.6 6 C9 6 7 7.6 6.3 9.9 C4.6 10.3 3.5 11.8 3.5 13.7 C3.5 16.2 5.4 18 7.3 18 Z" />`,
    `<path d="M12 8.9 V14.8" />`,
    `<path d="M9.6 12.6 L12 15 L14.4 12.6" />`,
  ],
  tag: [
    `<path d="M4.6 5.4 C4.6 4.9 4.9 4.6 5.4 4.6 H11.3 C11.8 4.6 12.2 4.8 12.6 5.2 L19 11.7 C19.5 12.2 19.5 12.9 19 13.4 L13.4 19 C12.9 19.5 12.2 19.5 11.7 19 L5.2 12.6 C4.8 12.2 4.6 11.8 4.6 11.3 Z" />`,
    `<circle cx="8.3" cy="8.3" r="1.15" />`,
    `<path d="M11.1 13.1 L13.5 15.5" />`,
  ],
  archive: [
    `<path d="M4.3 6.9 H19.7 V10.4 H4.3 Z" />`,
    `<path d="M6 10.4 V19.1 H18 V10.4" />`,
    `<path d="M9.1 14.1 H14.9" />`,
    `<path d="M8.1 6.9 V5.8 C8.1 5.3 8.5 4.9 9 4.9 H15 C15.5 4.9 15.9 5.3 15.9 5.8 V6.9" />`,
  ],
};

function ensureDir(filePath) {
  mkdirSync(path.dirname(filePath), { recursive: true });
}

function write(relativePath, contents) {
  const absolutePath = path.join(projectRoot, relativePath);
  ensureDir(absolutePath);
  writeFileSync(absolutePath, `${contents.trim()}\n`);
}

function renderPng(inputRelativePath, outputRelativePath, width, height) {
  const inputPath = path.join(projectRoot, inputRelativePath);
  const outputPath = path.join(projectRoot, outputRelativePath);
  ensureDir(outputPath);
  execFileSync("rsvg-convert", [
    "--width",
    String(width),
    "--height",
    String(height),
    inputPath,
    "-o",
    outputPath,
  ]);
}

function svgShell({ width, height, viewBox, body }) {
  return `
<svg xmlns="http://www.w3.org/2000/svg" width="${width}" height="${height}" viewBox="${viewBox}">
  ${body.trim()}
</svg>`;
}

function formatNumber(value) {
  return Number(value).toFixed(3).replace(/\.?0+$/, "");
}

function scalePathData(pathData, scale) {
  return pathData.replace(/-?\d*\.?\d+/g, (number) =>
    formatNumber(Number(number) * scale)
  );
}

function circlePathData({ cx, cy, r }, scale = 1) {
  const x = cx * scale;
  const y = cy * scale;
  const radius = r * scale;
  const control = radius * 0.5522847498;

  return [
    `M${formatNumber(x + radius)},${formatNumber(y)}`,
    `C${formatNumber(x + radius)},${formatNumber(y + control)} ${formatNumber(x + control)},${formatNumber(y + radius)} ${formatNumber(x)},${formatNumber(y + radius)}`,
    `C${formatNumber(x - control)},${formatNumber(y + radius)} ${formatNumber(x - radius)},${formatNumber(y + control)} ${formatNumber(x - radius)},${formatNumber(y)}`,
    `C${formatNumber(x - radius)},${formatNumber(y - control)} ${formatNumber(x - control)},${formatNumber(y - radius)} ${formatNumber(x)},${formatNumber(y - radius)}`,
    `C${formatNumber(x + control)},${formatNumber(y - radius)} ${formatNumber(x + radius)},${formatNumber(y - control)} ${formatNumber(x + radius)},${formatNumber(y)}`,
    "Z",
  ].join(" ");
}

function qMarkSvg({ stroke = colors.ink, drop = colors.coral, background = null }) {
  const backgroundMarkup = background
    ? `<rect width="84" height="84" fill="${background}" />`
    : "";
  return svgShell({
    width: 84,
    height: 84,
    viewBox: "0 0 84 84",
    body: `
      ${backgroundMarkup}
      <path d="${qMark84.ring}" fill="none" stroke="${stroke}" stroke-width="${qMarkStroke}" stroke-linecap="round" stroke-linejoin="round" />
      <path d="${qMark84.tail}" fill="none" stroke="${stroke}" stroke-width="${qMarkStroke}" stroke-linecap="round" stroke-linejoin="round" />
      <circle cx="${qMark84.drop.cx}" cy="${qMark84.drop.cy}" r="${qMark84.drop.r}" fill="${drop}" />
    `,
  });
}

function appIconSvg({ background, mark, drop }) {
  const iconScale = 9.8;
  const iconOffset = (1024 - 84 * iconScale) / 2;

  return svgShell({
    width: 1024,
    height: 1024,
    viewBox: "0 0 1024 1024",
    body: `
      <rect width="1024" height="1024" fill="${background}" />
      <g transform="translate(${formatNumber(iconOffset)} ${formatNumber(iconOffset)}) scale(${iconScale})">
        <path d="${qMark84.ring}" fill="none" stroke="${mark}" stroke-width="${qMarkStroke}" stroke-linecap="round" stroke-linejoin="round" />
        <path d="${qMark84.tail}" fill="none" stroke="${mark}" stroke-width="${qMarkStroke}" stroke-linecap="round" stroke-linejoin="round" />
        <circle cx="${qMark84.drop.cx}" cy="${qMark84.drop.cy}" r="${qMark84.drop.r}" fill="${drop}" />
      </g>
    `,
  });
}

function splashSvg({ background, mark, drop, wordmark, tagline, accent }) {
  return svgShell({
    width: 1170,
    height: 2532,
    viewBox: "0 0 1170 2532",
    body: `
      <rect width="1170" height="2532" fill="${background}" />
      <g transform="translate(525 1115) scale(1.4285714286)">
        <path d="${qMark84.ring}" fill="none" stroke="${mark}" stroke-width="${qMarkStroke}" stroke-linecap="round" stroke-linejoin="round" />
        <path d="${qMark84.tail}" fill="none" stroke="${mark}" stroke-width="${qMarkStroke}" stroke-linecap="round" stroke-linejoin="round" />
        <circle cx="${qMark84.drop.cx}" cy="${qMark84.drop.cy}" r="${qMark84.drop.r}" fill="${drop}" />
      </g>
      <text x="585" y="1300" text-anchor="middle" font-family="Cormorant Garamond, Georgia, serif" font-size="40" font-weight="500" letter-spacing="-0.4" fill="${wordmark}">QuickInk</text>
      <text x="585" y="1334" text-anchor="middle" font-family="Caveat, Bradley Hand, cursive" font-size="22" font-weight="400" fill="${tagline}">scan, jot, find again.</text>
      <rect x="573" y="2470" width="24" height="2" rx="1" fill="${accent}" />
    `,
  });
}

function iconSvg(paths) {
  return svgShell({
    width: 24,
    height: 24,
    viewBox: "0 0 24 24",
    body: `<g ${iconStroke.attrs}>${paths.join("")}</g>`,
  });
}

function contactSheetSvg() {
  const names = Object.keys(uiIcons);
  const positions = [
    [25, 20],
    [97, 20],
    [169, 20],
    [25, 94],
    [97, 94],
    [169, 94],
  ];
  const tiles = names
    .map((name, index) => {
      const [x, y] = positions[index];
      return `
        <g>
          <rect x="${x}" y="${y}" width="46" height="46" rx="12" fill="${colors.white}" stroke="${colors.border}" stroke-width="0.5" />
          <g transform="translate(${x + 11} ${y + 11})" ${iconStroke.attrs}>
            ${uiIcons[name].join("")}
          </g>
        </g>`;
    })
    .join("");

  return svgShell({
    width: 240,
    height: 160,
    viewBox: "0 0 240 160",
    body: `<rect width="240" height="160" fill="${colors.canvas}" />${tiles}`,
  });
}

function androidVector({ stroke, drop }) {
  const scale = 108 / 84;
  return `
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
    <path
        android:pathData="${scalePathData(qMark84.ring, scale)}"
        android:fillColor="@android:color/transparent"
        android:strokeColor="${stroke}"
        android:strokeWidth="${formatNumber(qMarkStroke * scale)}"
        android:strokeLineCap="round"
        android:strokeLineJoin="round" />
    <path
        android:pathData="${scalePathData(qMark84.tail, scale)}"
        android:fillColor="@android:color/transparent"
        android:strokeColor="${stroke}"
        android:strokeWidth="${formatNumber(qMarkStroke * scale)}"
        android:strokeLineCap="round"
        android:strokeLineJoin="round" />
    <path
        android:pathData="${circlePathData(qMark84.drop, scale)}"
        android:fillColor="${drop}" />
</vector>`;
}

write("design/assets/logo/quickink-mark.svg", qMarkSvg({}));

write(
  "design/assets/logo/quickink-logo-lockup.svg",
  svgShell({
    width: 240,
    height: 156,
    viewBox: "0 0 240 156",
    body: `
      <g transform="translate(78 16)">
        <path d="${qMark84.ring}" fill="none" stroke="${colors.ink}" stroke-width="${qMarkStroke}" stroke-linecap="round" stroke-linejoin="round" />
        <path d="${qMark84.tail}" fill="none" stroke="${colors.ink}" stroke-width="${qMarkStroke}" stroke-linecap="round" stroke-linejoin="round" />
        <circle cx="${qMark84.drop.cx}" cy="${qMark84.drop.cy}" r="${qMark84.drop.r}" fill="${colors.coral}" />
      </g>
      <text x="120" y="134" text-anchor="middle" font-family="Cormorant Garamond, Georgia, serif" font-size="34" font-weight="500" letter-spacing="-0.4" fill="${colors.ink}">QuickInk</text>
    `,
  })
);

write(
  "design/assets/app-icon/quickink-app-icon-coral.svg",
  appIconSvg({ background: colors.coral, mark: colors.canvas, drop: colors.ink })
);
write(
  "design/assets/app-icon/quickink-app-icon-cream.svg",
  appIconSvg({ background: colors.canvas, mark: colors.ink, drop: colors.coral })
);
write(
  "design/assets/app-icon/quickink-app-icon-ink.svg",
  appIconSvg({ background: colors.ink, mark: colors.canvas, drop: colors.coral })
);

write(
  "design/assets/splash/quickink-splash-cream.svg",
  splashSvg({
    background: colors.canvas,
    mark: colors.ink,
    drop: colors.coral,
    wordmark: colors.ink,
    tagline: colors.muted,
    accent: colors.coral,
  })
);
write(
  "design/assets/splash/quickink-splash-coral.svg",
  splashSvg({
    background: colors.coral,
    mark: colors.canvas,
    drop: colors.ink,
    wordmark: colors.canvas,
    tagline: "#F4E7DA",
    accent: colors.ink,
  })
);

for (const [name, paths] of Object.entries(uiIcons)) {
  write(`design/assets/ui-icons/${name}.svg`, iconSvg(paths));
}
write("design/assets/ui-icons/quickink-icons-contact-sheet.svg", contactSheetSvg());

write(
  "android/app/src/main/res/drawable/ic_launcher_background.xml",
  `
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
    <path
        android:pathData="M0,0 L108,0 L108,108 L0,108 Z"
        android:fillColor="${colors.coral}" />
</vector>`
);
write(
  "android/app/src/main/res/drawable/ic_launcher_foreground.xml",
  androidVector({ stroke: colors.canvas, drop: colors.ink })
);
write(
  "android/app/src/main/res/drawable/ic_launcher_monochrome.xml",
  androidVector({ stroke: "#FFFFFF", drop: "#FFFFFF" })
);
write(
  "android/app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml",
  `
<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@drawable/ic_launcher_background" />
    <foreground android:drawable="@drawable/ic_launcher_foreground" />
    <monochrome android:drawable="@drawable/ic_launcher_monochrome" />
</adaptive-icon>`
);
write(
  "android/app/src/main/res/mipmap-anydpi-v26/ic_launcher_round.xml",
  `
<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@drawable/ic_launcher_background" />
    <foreground android:drawable="@drawable/ic_launcher_foreground" />
    <monochrome android:drawable="@drawable/ic_launcher_monochrome" />
</adaptive-icon>`
);

write(
  "ios/QuickInk/Resources/Assets.xcassets/Contents.json",
  JSON.stringify({ info: { author: "xcode", version: 1 } }, null, 2)
);
write(
  "ios/QuickInk/Resources/Assets.xcassets/AppIcon.appiconset/Contents.json",
  JSON.stringify(
    {
      images: [
        {
          filename: "AppIcon-1024.png",
          idiom: "universal",
          platform: "ios",
          size: "1024x1024",
        },
      ],
      info: { author: "xcode", version: 1 },
    },
    null,
    2
  )
);
write(
  "ios/QuickInk/Resources/Assets.xcassets/QuickInkMark.imageset/Contents.json",
  JSON.stringify(
    {
      images: [
        {
          filename: "QuickInkMark.svg",
          idiom: "universal",
        },
      ],
      info: { author: "xcode", version: 1 },
    },
    null,
    2
  )
);
write("ios/QuickInk/Resources/Assets.xcassets/QuickInkMark.imageset/QuickInkMark.svg", qMarkSvg({}));

renderPng("design/assets/app-icon/quickink-app-icon-coral.svg", "design/assets/app-icon/quickink-app-icon-coral.png", 1024, 1024);
renderPng("design/assets/app-icon/quickink-app-icon-cream.svg", "design/assets/app-icon/quickink-app-icon-cream.png", 1024, 1024);
renderPng("design/assets/app-icon/quickink-app-icon-ink.svg", "design/assets/app-icon/quickink-app-icon-ink.png", 1024, 1024);
renderPng("design/assets/splash/quickink-splash-cream.svg", "design/assets/splash/quickink-splash-cream.png", 1170, 2532);
renderPng("design/assets/splash/quickink-splash-coral.svg", "design/assets/splash/quickink-splash-coral.png", 1170, 2532);
renderPng("design/assets/ui-icons/quickink-icons-contact-sheet.svg", "design/assets/ui-icons/quickink-icons-contact-sheet.png", 240, 160);
renderPng("design/assets/app-icon/quickink-app-icon-coral.svg", "ios/QuickInk/Resources/Assets.xcassets/AppIcon.appiconset/AppIcon-1024.png", 1024, 1024);

copyFileSync(
  path.join(projectRoot, "design/assets/logo/quickink-mark.svg"),
  path.join(projectRoot, "ios/QuickInk/Resources/Assets.xcassets/QuickInkMark.imageset/QuickInkMark.svg")
);

write(
  "design/assets/README.md",
  `
# QuickInk Brand Assets

Generated from \`design/assets/generate-assets.mjs\`.

- \`logo/quickink-mark.svg\`: primary Q ink mark.
- \`logo/quickink-logo-lockup.svg\`: mark plus QuickInk wordmark.
- \`app-icon/quickink-app-icon-*.svg|png\`: coral primary plus cream and ink alternates.
- \`splash/quickink-splash-*.svg|png\`: cream and coral launch compositions.
- \`ui-icons/*.svg\`: six 24 px line icons.
- \`ui-icons/quickink-icons-contact-sheet.png\`: 3 x 2 preview sheet.

Run \`node design/assets/generate-assets.mjs\` from \`apps/quickink\` after changing source geometry or colors. PNG rendering requires \`rsvg-convert\`.
`
);

console.log("Generated QuickInk SVG, PNG, Android, and iOS app-icon assets.");
