import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const prototypeDir = path.dirname(fileURLToPath(import.meta.url));
const appDir = path.join(prototypeDir, 'app');
const generatedImage = path.join(prototypeDir, 'assets', 'generated', 'core-specialty-clinical-kernel.png');
const outputPath = path.join(prototypeDir, 'prototype.html');
const scripts = ['app.js', 'extensions.js', 'coverage.js', 'specialties.js', 'specialty-v12.js', 'specialty-v13.js'];

const imageDataUri = `data:image/png;base64,${fs.readFileSync(generatedImage).toString('base64')}`;
const css = fs.readFileSync(path.join(appDir, 'styles.css'), 'utf8');
const javascript = scripts
  .map((name) => fs.readFileSync(path.join(appDir, name), 'utf8'))
  .join('\n')
  .replaceAll('../assets/generated/core-specialty-clinical-kernel.png', imageDataUri);

const html = `<!doctype html>
<html lang="zh-CN">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <meta name="prototype-version" content="0.13.0">
  <title>HaoEMR 核心临床原型 v0.13</title>
  <link rel="icon" href="data:,">
  <style>${css}</style>
</head>
<body>
  <div class="prototype-badge">交互原型 · 合成数据 · 非生产系统</div>
  <div id="app" data-screen-id="runtime-route"></div>
  <script>${javascript}</script>
</body>
</html>
`;

fs.writeFileSync(outputPath, html, 'utf8');
console.log(JSON.stringify({ output: outputPath, bytes: Buffer.byteLength(html), scripts: scripts.length, generatedImages: 1 }));
