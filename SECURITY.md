# Security policy

HaoEMR handles security-sensitive clinical workflows. Please report vulnerabilities privately and do not test against systems, accounts, or patient data that you do not own or have explicit authorization to use.

## Supported versions

HaoEMR is currently pre-release software. Until the first supported release is published, security fixes are applied to the `main` branch on a best-effort basis. Historical snapshots, generated demonstration packages, forks, and modified deployments are not maintained by this project.

| Version | Supported |
|---|---|
| `main` pre-release | Best effort |
| Historical or modified versions | No |

## Reporting a vulnerability

1. On the repository's **Security** tab, use **Report a vulnerability** if private vulnerability reporting is available.
2. If that option is unavailable, do not publish technical details. Open a minimal issue requesting a private security contact, without exploit steps, credentials, patient information, or affected production-system details.
3. Include the affected version or commit, impact, reproduction conditions, and a safe proof of concept only in the private channel.

Repository: <https://github.com/LIUgongxi2046/haoemr>

The maintainer will acknowledge a privately received report, validate it, coordinate remediation and disclosure, and credit the reporter when requested and appropriate. No response deadline or bounty is promised at this pre-release stage.

## Safe-testing boundaries

- Use only local or isolated environments and synthetic data.
- Never include real patient information, credentials, private keys, access tokens, or production endpoints in a report.
- Do not perform denial-of-service, destructive testing, persistence, lateral movement, social engineering, or data exfiltration.
- Stop testing after demonstrating the minimum evidence needed to explain the issue.

## Deployment warning

The presence of security controls or tests in this repository is not a certification. Operators must perform their own threat modeling, penetration testing, privacy assessment, clinical-safety review, backup and recovery validation, dependency monitoring, and regulatory review before any real deployment.
