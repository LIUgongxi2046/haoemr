#!/usr/bin/env node

import { execFileSync } from "node:child_process";
import { existsSync, readFileSync, readdirSync } from "node:fs";
import { homedir } from "node:os";
import { basename, join, relative, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const projectDir = resolve(fileURLToPath(new URL("..", import.meta.url)));
const webDir = join(projectDir, "web");
const nodeModulesDir = join(webDir, "node_modules");
const buildLibsDir = join(projectDir, "build", "libs");
const bootJar = existsSync(buildLibsDir) ? readdirSync(buildLibsDir)
  .filter((name) => name.endsWith(".jar"))
  .map((name) => join(buildLibsDir, name))[0] : null;

function walkPackageJson(directory, results) {
  if (!existsSync(directory)) return;
  for (const entry of readdirSync(directory, { withFileTypes: true })) {
    if (entry.name === ".bin") continue;
    const path = join(directory, entry.name);
    if (!entry.isDirectory()) continue;
    const packageJsonPath = join(path, "package.json");
    if (existsSync(packageJsonPath)) results.push(packageJsonPath);
    if (entry.name.startsWith("@")) walkPackageJson(path, results);
    const nestedModules = join(path, "node_modules");
    if (existsSync(nestedModules)) walkPackageJson(nestedModules, results);
  }
}

function normalizedLicense(value) {
  if (typeof value === "string" && value.trim()) return value.trim();
  if (Array.isArray(value)) return value.map(normalizedLicense).filter(Boolean).join(" OR ") || "UNKNOWN";
  if (value && typeof value === "object") return value.type || value.name || "UNKNOWN";
  return "UNKNOWN";
}

function countBy(items, keySelector) {
  return Object.fromEntries([...items.reduce((counts, item) => {
    const key = keySelector(item);
    counts.set(key, (counts.get(key) ?? 0) + 1);
    return counts;
  }, new Map())].sort(([left], [right]) => left.localeCompare(right)));
}

const packageJsonPaths = [];
walkPackageJson(nodeModulesDir, packageJsonPaths);
const frontendPackages = [...new Map(packageJsonPaths.map((path) => {
  const metadata = JSON.parse(readFileSync(path, "utf8"));
  const record = { name: metadata.name ?? basename(resolve(path, "..")), version: metadata.version ?? "UNKNOWN", license: normalizedLicense(metadata.license ?? metadata.licenses) };
  return [`${record.name}@${record.version}`, record];
})).values()].sort((left, right) => `${left.name}@${left.version}`.localeCompare(`${right.name}@${right.version}`));

const packageLock = JSON.parse(readFileSync(join(webDir, "package-lock.json"), "utf8"));
const registryHosts = [];
for (const metadata of Object.values(packageLock.packages ?? {})) {
  if (!metadata?.resolved) continue;
  try { registryHosts.push(new URL(metadata.resolved).hostname); } catch { registryHosts.push("INVALID_URL"); }
}

function cacheCoordinatesFor(artifactNames) {
  const cacheRoot = join(homedir(), ".gradle", "caches", "modules-2", "files-2.1");
  const matches = new Map();
  const pomIndex = new Map();
  if (!existsSync(cacheRoot)) return { matches, pomIndex };
  for (const group of readdirSync(cacheRoot, { withFileTypes: true })) {
    if (!group.isDirectory()) continue;
    const groupPath = join(cacheRoot, group.name);
    for (const module of readdirSync(groupPath, { withFileTypes: true })) {
      if (!module.isDirectory()) continue;
      const modulePath = join(groupPath, module.name);
      for (const version of readdirSync(modulePath, { withFileTypes: true })) {
        if (!version.isDirectory()) continue;
        const versionPath = join(modulePath, version.name);
        const files = [];
        for (const hashDir of readdirSync(versionPath, { withFileTypes: true })) {
          if (!hashDir.isDirectory()) continue;
          for (const file of readdirSync(join(versionPath, hashDir.name), { withFileTypes: true })) {
            if (file.isFile()) files.push(join(versionPath, hashDir.name, file.name));
          }
        }
        const jarPath = files.find((path) => artifactNames.has(basename(path)));
        const pomPath = files.find((path) => path.endsWith(".pom")) ?? null;
        if (pomPath) pomIndex.set(`${group.name}:${module.name}:${version.name}`, pomPath);
        if (!jarPath) continue;
        matches.set(basename(jarPath), {
          group: group.name,
          module: module.name,
          version: version.name,
          jarPath,
          pomPath,
        });
      }
    }
  }
  return { matches, pomIndex };
}

function xmlElement(block, name) {
  return block.match(new RegExp(`<${name}>([\\s\\S]*?)<\\/${name}>`, "i"))?.[1].replace(/\s+/g, " ").trim() ?? null;
}

function pomLicenseNames(pomPath, pomIndex, visited = new Set()) {
  if (!pomPath) return [];
  if (visited.has(pomPath)) return [];
  visited.add(pomPath);
  const xml = readFileSync(pomPath, "utf8");
  const licenseBlock = xml.match(/<licenses>[\s\S]*?<\/licenses>/i)?.[0] ?? "";
  const direct = [...licenseBlock.matchAll(/<name>([\s\S]*?)<\/name>/gi)].map((match) => match[1].replace(/\s+/g, " ").trim());
  if (direct.length > 0) return direct;
  const parentBlock = xml.match(/<parent>[\s\S]*?<\/parent>/i)?.[0];
  if (!parentBlock) return [];
  const parentGroup = xmlElement(parentBlock, "groupId");
  const parentModule = xmlElement(parentBlock, "artifactId");
  const parentVersion = xmlElement(parentBlock, "version");
  if (!parentGroup || !parentModule || !parentVersion) return [];
  return pomLicenseNames(pomIndex.get(`${parentGroup}:${parentModule}:${parentVersion}`), pomIndex, visited);
}

function bundledLicenseEvidence(jarPath) {
  if (!jarPath) return [];
  let entries;
  try {
    entries = execFileSync("unzip", ["-Z1", jarPath], { encoding: "utf8", maxBuffer: 8 * 1024 * 1024 });
  } catch {
    return [];
  }
  return entries.split("\n").filter((entry) => /(^|\/)(LICENSE|NOTICE|COPYING)(\.|$)/i.test(entry)).slice(0, 10);
}

let backendJarNames = [];
if (bootJar && existsSync(bootJar)) {
  backendJarNames = execFileSync("unzip", ["-Z1", bootJar], { encoding: "utf8", maxBuffer: 16 * 1024 * 1024 })
    .split("\n").filter((entry) => /^BOOT-INF\/lib\/.*\.jar$/.test(entry)).map((entry) => basename(entry)).sort();
}
const { matches: cacheMatches, pomIndex } = cacheCoordinatesFor(new Set(backendJarNames));
const backendPackages = backendJarNames.map((file) => {
  const coordinate = cacheMatches.get(file);
  const declaredLicenses = pomLicenseNames(coordinate?.pomPath, pomIndex);
  const bundledLicenseFiles = bundledLicenseEvidence(coordinate?.jarPath);
  return {
    file,
    coordinate: coordinate ? `${coordinate.group}:${coordinate.module}:${coordinate.version}` : "UNRESOLVED",
    declaredLicenses,
    bundledLicenseFiles,
    status: declaredLicenses.length > 0 ? "DECLARED" : bundledLicenseFiles.length > 0 ? "BUNDLED_LICENSE_REVIEW" : "UNKNOWN",
  };
});

const trackedPaths = execFileSync("git", ["ls-files"], { cwd: projectDir, encoding: "utf8", maxBuffer: 64 * 1024 * 1024 }).split("\n").filter(Boolean);
const mediaExtensions = new Set([".png", ".jpg", ".jpeg", ".svg", ".gif", ".webp", ".ico"]);
const trackedMedia = trackedPaths.filter((path) => mediaExtensions.has(path.slice(path.lastIndexOf(".")).toLowerCase()));
const assetManifestPaths = trackedPaths.filter((path) => path.endsWith("asset-manifest.csv"));
const assetManifestRecords = assetManifestPaths.reduce((total, path) => total + Math.max(0, readFileSync(join(projectDir, path), "utf8").trim().split("\n").length - 1), 0);

const authors = execFileSync("git", ["log", "--branches", "--remotes", "--tags", "--format=%an <%ae>"], { cwd: projectDir, encoding: "utf8" }).trim().split("\n").filter(Boolean);
const commitBodies = execFileSync("git", ["log", "--branches", "--remotes", "--tags", "--format=%H%x00%B%x00"], { cwd: projectDir, encoding: "utf8", maxBuffer: 64 * 1024 * 1024 }).split("\0");
let signedOffCommits = 0;
for (let index = 1; index < commitBodies.length; index += 2) {
  if (/^Signed-off-by:/im.test(commitBodies[index])) signedOffCommits += 1;
}

const result = {
  status: frontendPackages.length === 0 || !bootJar || frontendPackages.some(({ license }) => license === "UNKNOWN") || backendPackages.some(({ status }) => status !== "DECLARED") || trackedMedia.length > assetManifestRecords ? "REVIEW_REQUIRED" : "PASS",
  frontend: {
    packageCount: frontendPackages.length,
    licenseCounts: countBy(frontendPackages, ({ license }) => license),
    packagesWithoutLicense: frontendPackages.filter(({ license }) => license === "UNKNOWN"),
    registryHostCounts: countBy(registryHosts, (host) => host),
    packagesRequiringFocusedReview: frontendPackages.filter(({ license }) => /MPL|Unlicense|GPL|LGPL|AGPL|UNKNOWN/i.test(license)),
  },
  backend: {
    artifact: bootJar ? relative(projectDir, bootJar) : null,
    packagedJarCount: backendPackages.length,
    statusCounts: countBy(backendPackages, ({ status }) => status),
    declaredLicenseCounts: countBy(backendPackages.filter(({ declaredLicenses }) => declaredLicenses.length > 0), ({ declaredLicenses }) => declaredLicenses.join(" OR ")),
    packagesRequiringReview: backendPackages.filter(({ status }) => status !== "DECLARED"),
  },
  assets: {
    trackedMediaCount: trackedMedia.length,
    extensionCounts: countBy(trackedMedia, (path) => path.slice(path.lastIndexOf(".")).toLowerCase()),
    assetManifestPaths,
    assetManifestRecords,
    note: "A manifest record is not assumed to map one-to-one to every tracked screenshot or generated artifact; classification and release-scope review are still required.",
  },
  contributionProvenance: {
    reachableCommitCount: Number(execFileSync("git", ["rev-list", "--count", "--branches", "--remotes", "--tags"], { cwd: projectDir, encoding: "utf8" }).trim()),
    uniqueAuthors: [...new Set(authors)].sort(),
    signedOffCommits,
  },
  limitations: [
    "Frontend inventory requires installed node_modules and reflects this working copy.",
    "Backend inventory reflects the existing Spring Boot artifact and local Gradle cache; it is not an authoritative SBOM.",
    "POM license metadata can be inherited or incomplete, so UNKNOWN and BUNDLED_LICENSE_REVIEW are manual-review states, not claims that a component is unlicensed.",
    "Binary asset ownership, patient-data content, model output terms, medical terminology, and standards data cannot be proven by filename scanning.",
  ],
};

console.log(JSON.stringify(result, null, 2));
process.exitCode = result.status === "PASS" ? 0 : 2;
