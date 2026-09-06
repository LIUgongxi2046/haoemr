# Third-party notices and provenance

Status: **initial inventory; not a completed release compliance review**  
Inventory date: 2026-09-06

HaoEMR includes and depends on third-party software. Those components remain under their own licenses; the HaoEMR AGPL license does not replace them. Before a public release, the complete resolved dependency graph, packaged artifacts, copied code, fonts, images, medical terminology, standards extracts, and sample data must be reviewed and a release-specific SBOM and notice bundle must be generated.

## Direct frontend dependencies

The following values were read from `web/package.json`, `web/package-lock.json`, and the installed package metadata on the inventory date. They cover direct dependencies only, not the full transitive graph.

| Package | Resolved version | Declared license |
|---|---:|---|
| `@tanstack/vue-query` | 5.101.4 | MIT |
| `@vitejs/plugin-vue` | 6.0.8 | MIT |
| `d3` | 7.9.0 | ISC |
| `pinia` | 3.0.4 | MIT |
| `typescript` | 6.0.3 | Apache-2.0 |
| `vite` | 8.2.1 | MIT |
| `vue` | 3.5.41 | MIT |
| `vue-router` | 4.6.4 | MIT |
| `zod` | 4.4.3 | MIT |
| `@types/d3` | 7.4.3 | MIT |
| `@types/node` | 24.13.3 | MIT |
| `playwright` | 1.61.1 | Apache-2.0 |
| `vitest` | 4.1.10 | MIT |
| `vue-tsc` | 3.3.10 | MIT |

## Resolved dependency audit snapshot

The 2026-09-06 local audit found 168 unique installed frontend packages, all with declared license metadata. The license groups were MIT, ISC, Apache-2.0, BSD-2-Clause, BSD-3-Clause, MPL-2.0, and Unlicense. `lightningcss` and its local platform package are MPL-2.0; `robust-predicates` is Unlicense. These licenses continue to apply to their respective components and their notices must be preserved.

The existing Spring Boot artifact contained 72 runtime JARs. The local POM/parent-POM audit resolved declared license metadata for 71. `spring-boot-jarmode-tools-4.1.0.jar` could not be resolved as an independent local Gradle-cache coordinate; it is a Spring Boot component, and the upstream Spring Boot project is Apache-2.0, but a release SBOM must still preserve its exact provenance.

This snapshot is summarized in `docs/process/security/2026-09-06-p4-open-source-compliance-audit.md`. It is not a substitute for a release-specific SBOM, full license texts, and notice bundle generated from the actual shipped artifact.

## Project assets and data

- Synthetic JSON fixtures under `samples/` are intended only for testing and must not contain real patient information.
- AI-generated and project-created visual assets are recorded in the asset manifests under `docs/design/ui-delivery/`. Their redistribution status must be reconciled with the repository license before a release.
- `web/public/assets/record-center/pacs-ultrasound-reference.png` and any other reference media require explicit provenance and redistribution verification before a release.
- Medical terminology, drug knowledge, coding systems, standards text, and institutional templates are not automatically open data. Each imported dataset requires an owner, version, permitted use, source URL, checksum, and license record.

## Release blockers

A public release must be blocked when any shipped component has an unknown source, unknown license, incompatible obligation, missing required notice, secret, or real patient information. This initial file must be replaced or supplemented by a release-specific, machine-generated SBOM and corresponding license/notice bundle.
