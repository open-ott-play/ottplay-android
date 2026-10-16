# OpenSSF Passing evidence

This is a source-backed work record for the [OpenSSF Best Practices Passing
criteria](https://www.bestpractices.dev/en/criteria/0). It does not assert that a
badge has been awarded. The partial [machine-readable assessment](../.bestpractices.json)
leaves unverified criteria unknown.

## Available evidence

- Purpose, installation and operating limitations: [README](../README.md).
- Public Git source, commit history and change proposals: [open-ott-play/ottplay-android](https://github.com/open-ott-play/ottplay-android).
- Bug reports and retained discussion: [issues](https://github.com/open-ott-play/ottplay-android/issues).
- Contribution requirements, test policy and test invocation: [CONTRIBUTING](../CONTRIBUTING.md).
- Private reporting process and response policy: [SECURITY](../SECURITY.md).
- Trust boundaries, source references and negative-test evidence: [security design](security-design.md).
- Build/test automation: [workflow](../.github/workflows/android.yml) and [actual run results](https://github.com/open-ott-play/ottplay-android/actions).
- Project license: [MIT](../LICENSE); third-party terms still apply.

## Initial scanner inventory — 2026-10-08

The authenticated GitHub inventory found 9 open code-scanning alerts
(including Scorecard policy findings) and 0 open Dependabot alerts.
This is a dated baseline, not a statement about the post-merge state. A scanner
configuration error or disabled scanner must not be recorded as zero findings.
Check [Code scanning](https://github.com/open-ott-play/ottplay-android/security/code-scanning) and
[Dependabot](https://github.com/open-ott-play/ottplay-android/security/dependabot) after the changed default branch is
analyzed.

## Unresolved criteria and applicability

Review every distribution variant against transport/cryptography criteria and record exact device/emulator evidence. Signed release metadata and privacy-policy behavior must match the released APK.

On 2026-10-16 the maintainer confirmed that secure-design/common-error knowledge
and the absence of outside-GitHub bug, enhancement and vulnerability reports
over the preceding 12 months apply to this project. The public issue inventory
contains no non-PR reports, and private vulnerability reporting is enabled.
The current open code-scanning inventory contains eight Scorecard process
findings and no CodeQL source finding. Zero open Dependabot alerts are an
inventory result, not a complete dependency audit.

The current signed preview is
[v0.2.1-preview.5](https://github.com/open-ott-play/ottplay-android/releases/tag/v0.2.1-preview.5),
with version code 5 and source `b34fd3ff8741eac40db78a81864d34771d6b3156`.
Its public release manifest records the same preview signing certificate as
the release runbook. This source already includes the exact certificate-key
policy; later source changes still need separate release qualification.

Review each cryptographic criterion across the complete application, external
DRM/platform components and build/release tools. Measure whole-source coverage
and review per-language analysis; a passing host suite or one TLS test matrix
does not establish all these properties. Do not transfer another repository's
test results or badge identifier.

[Governance](../GOVERNANCE.md), [conduct rules](../CODE_OF_CONDUCT.md), and the
[roadmap](../ROADMAP.md) describe present responsibilities and limitations.
No backup maintainer or tested one-week access-recovery mechanism exists.

Record each completed analysis with its source SHA, commands/tool versions,
result and material limitations. Add the repository's actual badge only after
its own public assessment is saved and verified.
