import { createRequire } from "node:module";
import { existsSync, mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { basename, dirname, join, resolve } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";
import { spawnSync } from "node:child_process";

const sourceRoot = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const modulesRoot = resolve(process.env.CENTRAL_ICONS_MODULES ?? join(sourceRoot, "node_modules"));
const outputRoot = resolve(process.env.CENTRAL_ICON_OUTPUT_ROOT ?? sourceRoot);
const packageName = "@central-icons-react/round-outlined-radius-2-stroke-1.5";
const packageRoot = join(modulesRoot, packageName);
const manifestPath = join(sourceRoot, "shared", "icons-manifest.json");

if (!existsSync(join(packageRoot, "package.json"))) {
  throw new Error(`Central icons package was not found at ${packageRoot}. Set CENTRAL_ICONS_MODULES to the licensed installation's node_modules directory.`);
}

const require = createRequire(join(modulesRoot, "package.json"));
const React = require("react");
const { renderToStaticMarkup } = require("react-dom/server");
const { icons } = JSON.parse(readFileSync(manifestPath, "utf8"));
const svgRoot = join(outputRoot, "build", "central-icons", "svg");
const macRoot = join(outputRoot, "mac", "Resources", "icons");
const androidRoot = join(outputRoot, "android", "res", "drawable");

for (const directory of [svgRoot, macRoot, androidRoot]) mkdirSync(directory, { recursive: true });

for (const [name, definition] of Object.entries(icons)) {
  const options = typeof definition === "string" ? { component: definition } : definition;
  const componentName = options.component;
  const entry = join(modulesRoot, options.package ?? packageName, componentName, "index.mjs");
  if (!existsSync(entry)) throw new Error(`Central component ${componentName} for ${name} is unavailable in the licensed package.`);
  const componentModule = await import(pathToFileURL(entry).href);
  const Component = componentModule[componentName] ?? componentModule.default;
  if (!Component) throw new Error(`Central component ${componentName} did not export a React component.`);
  let svg = renderToStaticMarkup(React.createElement(Component, { mode: "raw", color: "#ffffff", size: 128, ariaHidden: true })).replaceAll("currentColor", "#ffffff");
  if (options.mirror) svg = svg.replace(/(<svg[^>]*>)([\s\S]*)(<\/svg>)/, '$1<g transform="translate(24 0) scale(-1 1)">$2</g>$3');
  const svgPath = join(svgRoot, `${name}.svg`);
  writeFileSync(svgPath, svg);
  for (const pngPath of [join(macRoot, `${name}.png`), join(androidRoot, `${name}.png`)]) {
    const result = spawnSync("sips", ["-s", "format", "png", svgPath, "--out", pngPath], { encoding: "utf8" });
    if (result.status !== 0) throw new Error(`sips could not rasterize ${basename(svgPath)}: ${result.stderr || result.stdout}`);
  }
}

console.log(`Exported ${Object.keys(icons).length} Central icons to ${outputRoot}.`);
