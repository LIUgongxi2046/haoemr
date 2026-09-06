# Contributing to HaoEMR

Thank you for helping improve HaoEMR. This project welcomes issues, documentation, tests, design improvements, and code contributions that respect clinical safety, patient privacy, software licensing, and the project's scope.

## Before contributing

- Search existing issues and pull requests before starting substantial work.
- Discuss large features, database changes, security architecture, clinical rules, or breaking API changes in an issue first.
- Read [`DEVELOPMENT_PRINCIPLES.md`](DEVELOPMENT_PRINCIPLES.md).
- Never submit real patient data, credentials, private keys, access tokens, private endpoints, or confidential institutional material.
- Use synthetic data in examples and tests.
- Identify the source and license of third-party code, data, terminology, images, standards extracts, or generated assets.

Security vulnerabilities must follow [`SECURITY.md`](SECURITY.md), not the public issue workflow.

## License and contributor agreement

HaoEMR is licensed under `AGPL-3.0-only`. Contributions intended for inclusion in the project are provided under the same license and are also subject to [`CLA.md`](CLA.md), which lets the Project Steward continue offering an alternative commercial license.

Every pull request must include this statement:

```text
I have read and agree to the HaoEMR Contributor License Agreement (CLA.md) for this contribution.
```

Every commit must include a [Developer Certificate of Origin 1.1](https://developercertificate.org/) sign-off:

```bash
git commit --signoff
```

The sign-off certifies that you have the right to submit the work. It does not replace the CLA.

## Development workflow

1. Fork the repository and create a focused branch.
2. Make the smallest coherent change and add or update tests.
3. Update contracts and documentation when behavior, configuration, APIs, schemas, or operational steps change.
4. Run the relevant checks. The full local gate is:

   ```bash
   scripts/verify.sh
   ```

5. Open a pull request using the repository template and describe clinical risk, data changes, compatibility, verification evidence, and license provenance.

## Review requirements

Changes may be rejected or held when they:

- weaken patient isolation, authorization, auditability, signing, versioning, safety gates, or fail-closed behavior;
- introduce real or insufficiently de-identified health information;
- depend on unlicensed or incompatible code, content, terminology, or assets;
- silently change a clinical rule or machine contract without tests and documentation;
- add secrets, generated build output, or unrelated formatting churn;
- cannot be safely validated in a local synthetic environment.

Acceptance of a contribution does not certify it for clinical or production use.
