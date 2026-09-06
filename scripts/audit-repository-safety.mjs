#!/usr/bin/env node

import { execFileSync, spawn } from "node:child_process";
import { createHash } from "node:crypto";
import { existsSync, lstatSync, readFileSync } from "node:fs";
import { extname, join, relative, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const projectDir = resolve(fileURLToPath(new URL("..", import.meta.url)));
const maxFileBytes = 5 * 1024 * 1024;
const excludedDirectories = new Set([".git", ".gradle", "node_modules", "build", "dist"]);
const scannerPath = "scripts/audit-repository-safety.mjs";
const textExtensions = new Set([
  "", ".cjs", ".conf", ".csv", ".env", ".html", ".java", ".js", ".json", ".jsx",
  ".kts", ".md", ".mjs", ".properties", ".py", ".sh", ".sql", ".svg", ".toml",
  ".ts", ".tsx", ".txt", ".vue", ".xml", ".yaml", ".yml",
]);

const secretRules = [
  ["private-key", /-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----/g],
  ["aws-access-key", /(?:^|[^A-Z0-9])(?:AKIA|ASIA)[A-Z0-9]{16}(?:[^A-Z0-9]|$)/g],
  ["openai-compatible-key", /(?:^|[^A-Za-z0-9])sk-[A-Za-z0-9_-]{24,}/g],
  ["github-token", /(?:gh[pousr]_[A-Za-z0-9]{30,}|github_pat_[A-Za-z0-9_]{22,})/g],
  ["google-api-key", /AIza[A-Za-z0-9_-]{35}/g],
  ["slack-token", /xox[baprs]-[A-Za-z0-9-]{20,}/g],
  ["stripe-live-key", /(?:sk|rk)_live_[A-Za-z0-9]{20,}/g],
  ["huggingface-token", /(?:^|[^A-Za-z0-9])hf_[A-Za-z0-9]{30,}/g],
  ["npm-token", /(?:^|[^A-Za-z0-9])npm_[A-Za-z0-9]{30,}/g],
];

const phiRules = [
  ["mainland-id-number", /(?:^|[^0-9])[1-9][0-9]{5}(?:18|19|20)[0-9]{2}(?:0[1-9]|1[0-2])(?:0[1-9]|[12][0-9]|3[01])[0-9]{3}[0-9Xx](?:[^0-9Xx]|$)/g],
  ["mainland-mobile", /(?:^|[^0-9])1[3-9][0-9]{9}(?:[^0-9]|$)/g],
  ["patient-identifier-label", /(?:患者姓名|身份证号|住院号|门诊号|病历号)[^\r\n]{0,16}(?:[:：=]|\"\s*:)/g],
];

function redactFingerprint(rule, path) {
  return createHash("sha256").update(`${rule}\0${path}`).digest("hex").slice(0, 12);
}

function scanContent(content, filePath, rules) {
  if (filePath === scannerPath) return [];
  const findings = [];
  for (const [rule, regex] of rules) {
    regex.lastIndex = 0;
    if (regex.test(content)) {
      findings.push({ rule, path: filePath, fingerprint: redactFingerprint(rule, filePath) });
    }
  }
  return findings;
}

function isTextPath(path) {
  const name = path.split("/").at(-1) ?? path;
  return textExtensions.has(extname(name).toLowerCase()) || name.startsWith(".env");
}

function isExcludedPath(path) {
  return path.split("/").some((segment) => excludedDirectories.has(segment));
}

async function scanHistory(rules) {
  const objectLines = execFileSync("git", ["rev-list", "--objects", "--branches", "--remotes", "--tags"], {
    cwd: projectDir,
    encoding: "utf8",
    maxBuffer: 64 * 1024 * 1024,
  }).trim().split("\n").filter(Boolean);
  const objectPaths = new Map();
  for (const line of objectLines) {
    const separator = line.indexOf(" ");
    if (separator < 0) continue;
    const object = line.slice(0, separator);
    const path = line.slice(separator + 1);
    if (isTextPath(path) && !isExcludedPath(path) && !objectPaths.has(object)) objectPaths.set(object, path);
  }
  if (objectPaths.size === 0) return { findings: [], blobsScanned: 0, blobsSkippedBySize: 0 };

  const objectInput = `${[...objectPaths.keys()].join("\n")}\n`;
  const checks = execFileSync("git", ["cat-file", "--batch-check=%(objectname) %(objecttype) %(objectsize)"], {
    cwd: projectDir,
    encoding: "utf8",
    input: objectInput,
    maxBuffer: 128 * 1024 * 1024,
  });
  const eligible = [];
  let blobsSkippedBySize = 0;
  for (const line of checks.trim().split("\n")) {
    const [object, type, rawSize] = line.split(" ");
    const size = Number(rawSize);
    if (type !== "blob") continue;
    if (size > maxFileBytes) {
      blobsSkippedBySize += 1;
      continue;
    }
    eligible.push(object);
  }
  const findings = [];
  await new Promise((resolvePromise, rejectPromise) => {
    const child = spawn("git", ["cat-file", "--batch"], { cwd: projectDir, stdio: ["pipe", "pipe", "pipe"] });
    let pending = Buffer.alloc(0);
    let currentHeader = null;
    let standardError = "";
    child.stderr.setEncoding("utf8");
    child.stderr.on("data", (chunk) => { standardError += chunk; });
    child.stdout.on("data", (chunk) => {
      pending = Buffer.concat([pending, chunk]);
      while (true) {
        if (currentHeader === null) {
          const headerEnd = pending.indexOf(10);
          if (headerEnd < 0) break;
          const [object, type, rawSize] = pending.subarray(0, headerEnd).toString("utf8").split(" ");
          currentHeader = { object, type, size: Number(rawSize) };
          pending = pending.subarray(headerEnd + 1);
        }
        if (!Number.isFinite(currentHeader.size) || pending.length < currentHeader.size + 1) break;
        const contentBuffer = pending.subarray(0, currentHeader.size);
        pending = pending.subarray(currentHeader.size + 1);
        if (currentHeader.type === "blob" && !contentBuffer.includes(0)) {
          const path = objectPaths.get(currentHeader.object) ?? "<unknown>";
          const content = contentBuffer.toString("utf8");
          for (const finding of scanContent(content, path, rules)) {
            findings.push({ ...finding, object: currentHeader.object.slice(0, 12) });
          }
        }
        currentHeader = null;
      }
    });
    child.on("error", rejectPromise);
    child.on("close", (status) => {
      if (status === 0) resolvePromise();
      else rejectPromise(new Error(`git cat-file history scan failed with status ${status}: ${standardError.trim()}`));
    });
    child.stdin.end(`${eligible.join("\n")}\n`);
  });
  return { findings, blobsScanned: eligible.length, blobsSkippedBySize };
}

const candidatePaths = execFileSync("git", ["ls-files", "-co", "--exclude-standard", "-z"], {
  cwd: projectDir,
  encoding: "utf8",
  maxBuffer: 128 * 1024 * 1024,
}).split("\0").filter(Boolean);
const files = [];
const skipped = { large: 0, nonText: 0, binary: 0 };
for (const projectPath of candidatePaths) {
  if (isExcludedPath(projectPath)) continue;
  const absolutePath = join(projectDir, projectPath);
  if (!existsSync(absolutePath)) continue;
  const stat = lstatSync(absolutePath);
  if (!stat.isFile() || stat.isSymbolicLink()) continue;
  if (stat.size > maxFileBytes) {
    skipped.large += 1;
    continue;
  }
  if (!isTextPath(projectPath)) {
    skipped.nonText += 1;
    continue;
  }
  files.push(absolutePath);
}

const currentSecrets = [];
const currentPhiCandidates = [];
for (const absolutePath of files) {
  const buffer = readFileSync(absolutePath);
  if (buffer.includes(0)) {
    skipped.binary += 1;
    continue;
  }
  const projectPath = relative(projectDir, absolutePath);
  const content = buffer.toString("utf8");
  currentSecrets.push(...scanContent(content, projectPath, secretRules));
  currentPhiCandidates.push(...scanContent(content, projectPath, phiRules));
}

const secretRuleNames = new Set(secretRules.map(([name]) => name));
const historyScan = await scanHistory([...secretRules, ...phiRules]);
const historySecrets = historyScan.findings.filter(({ rule }) => secretRuleNames.has(rule));
const historyPhiCandidates = historyScan.findings.filter(({ rule }) => !secretRuleNames.has(rule));

const trackedSensitiveFiles = execFileSync("git", ["ls-files"], {
  cwd: projectDir,
  encoding: "utf8",
}).split("\n").filter((path) => /(^|\/)\.env($|\.)|\.(pem|key|p12|pfx|jks|keystore)$/i.test(path));

const result = {
  status: currentSecrets.length === 0 && historySecrets.length === 0 && currentPhiCandidates.length === 0 && historyPhiCandidates.length === 0 ? "PASS" : "REVIEW_REQUIRED",
  credentialStatus: currentSecrets.length === 0 && historySecrets.length === 0 ? "PASS" : "REVIEW_REQUIRED",
  phiStatus: currentPhiCandidates.length === 0 && historyPhiCandidates.length === 0 ? "PASS" : "REVIEW_REQUIRED",
  scope: {
    currentTextFilesScanned: files.length - skipped.binary,
    reachableGitCommits: Number(execFileSync("git", ["rev-list", "--count", "--branches", "--remotes", "--tags"], { cwd: projectDir, encoding: "utf8" }).trim()),
    reachableGitTextBlobsScanned: historyScan.blobsScanned,
    reachableGitBlobsSkippedBySize: historyScan.blobsSkippedBySize,
    maxFileBytes,
    excludedDirectories: [...excludedDirectories],
    skipped,
  },
  currentSecrets,
  historySecrets,
  currentPhiCandidates,
  historyPhiCandidates,
  trackedSensitiveFiles,
  note: "Matches contain file paths and one-way fingerprints only; no matched values are emitted. PHI candidates require contextual review, and binary media requires a separate provenance/OCR review.",
};

console.log(JSON.stringify(result, null, 2));
process.exitCode = result.status === "PASS" ? 0 : 2;
