# P8-T18 source secret-finding review

## Final accepted checkpoint (2026-10-18)

Clean43ae0f3 full source security PASSes with235 components, zero SAST/advisory
matches and10 total/10 exactly accepted/zero unreviewed detector rows. Canonical
LF/strict-span controls pass natively;228 offline controls pass on Windows and
pinned Linux. Both hosted jobs pass in
[run39110490452](https://github.com/viet2109/flowpay/actions/runs/39110490452).
The [exit audit](production-exit-audit.md) closes Phase8 in the selected lab
scope, not public deployment. The chronological review checkpoints below retain
their original restrictions and rejected attempts. Approval scope, all five
whole-file identities, scanner/config and exclusive2026-11-16T18:22:02Z expiry
remain unchanged; no new finding, `.env` or image-secret waiver is accepted.

## Decision and authority

2026-10-18: the owner authorized continuing the individual finding review after
confirming that no real secret was required. The initial review below preserved
the earlier no-exception decision. The owner subsequently explicitly approved
the bounded false-positive acceptance described in the next section. This is
not permission to accept real/new secrets or declare a Phase8 freeze.

## Approved bounded false-positive acceptance

On2026-10-18 (Asia/Saigon), the owner instructed proceeding with the proposed
exact-location/content acceptance. `ops/ci/secret-reviews.json` records that
approval for these seven source locations and ten observed detector rows only.
It is separate from `ops/ci/exceptions.json`, which stays empty: dependency/SAST
exceptions and image secret acceptance are not authorized.

### Owner-approved LF detector metadata correction (2026-10-18)

Hosted run39109618819 fails with the fixed annotation
`stage=severity-policy; code=source-review-count`. Native reproduction confirms
that CRLF versus LF changes one detector end coordinate, not a credential:
the two rows for the same synthetic test-profile setting at line28 become
spans28–28 and28–29 under LF. All five whole-file LF identities and all ten
detector rows remain present. The original seven reviewed source-value
locations are unchanged; no new value is accepted.

The owner explicitly delegated proceeding with canonical LF and these exact
metadata on2026-10-18. Security snapshots normalize Git-classified text only in
the temporary copy, never checkout/fixture/binary bytes. `source-fp-07` alone
declares `findingSpans`: exactly one28–28 row and one28–29 row, both mandatory.
The other six entries are unchanged. This is not a range/line-ending tolerance,
fallback, duplicate folding or permission to accept fewer/additional rows.
Missing/shifted/duplicate/extra spans fail. Seven reviewed positions/ten rows,
hashes, pinned detector/config and original exclusive expiry remain bounded.
No auto renewal, fixture rewrite, new source waiver or image-secret exception.

Each entry binds the rule, exact test/doc path and line, occurrence count and
LF-normalized **whole source-file** SHA-256. The policy also binds the pinned
Gitleaks image and config checksum. These are public source-file identities,
not exported secret hashes or matched values. Different file contents (even on
another line), shifted locations, changed counts, missing evidence, stale
unmatched entries, scanner/config changes, malformed policy or expiry fail
closed; new findings remain blocking. No glob, directory-wide waiver, prefix
match, basename match or production/main-source acceptance is supported.

Approval is recorded at2026-10-17T18:22:02Z (2026-10-18 01:22:02 UTC+07) and
expires **exclusively at2026-11-16T18:22:02Z** (2026-11-17 01:22:02 UTC+07),
at most30 days. There is no automatic renewal or fingerprint refresh. Future
review/removal needs another owner decision. Recorded `approvedBy` is audit
metadata, not cryptographic proof of reviewer identity; require human review of
policy changes in the normal repository workflow.

P8-T18 continuation hardens release-time expiry too: safe source reports record
the earliest accepted review deadline, and release preflight checks it at the
current time, not merely that the scan is within72 hours. Complete finding
accounting is required. Missing/invalid/naive/expired deadlines block release;
zero-finding scans do not require a false-positive review. This does not change
the approved manifest, scope or expiry.212 offline controls pass on Windows,
including exact-boundary, earliest-deadline, accounting and actual preflight
regressions; matching Linux and full native source repetitions remain pending.
The real preflight rejects the earlier report with
`source secret-review expiry missing`. That report remains untouched; a fresh
scan must produce the new metadata, and clean committed-source acceptance is
still independently required. The source pass recorded below predates this
additional release-time check.

Gitleaks continues reporting all ten matches. Safe evidence preserves total,
accepted and unreviewed counts plus review IDs; acceptance is evaluated after
the complete source/dependency scan, not by disabling detection. Source scans
ignore inline `gitleaks:allow` instructions, with a native adversarial control.
Image scans do not consume source reviews. No test fixture, API, financial
behavior, runtime configuration, scanner binary or detection regex is changed.

Implementation verification: **full source/dependency security gate PASS**.
It retains10 total detector rows,10 explicitly accepted, zero unreviewed, all
seven review IDs, zero SAST findings and235 Maven components with zero advisory
matches. Native built-in/custom/inline-suppression canaries, all seven SAST
canaries, clean controls and vulnerable/unavailable-DB controls pass. Fresh
GrypeDBv6.1.10 is built2026-10-17T06:27:09Z; no stale/offline DB waiver is used.

All203 offline controls pass on Windows and pinned Linux Python3.13.12, including
20 dedicated acceptance-policy regressions. Separate native synthetic controls
prove exact acceptance and continued blocking for a new inline-suppressed
finding, changed approved-file contents and exclusive expiry. Runbook/source/
policy checks and diff/link checks pass separately. Application/build/test inputs
and the exact verified JAR/image remain unchanged; full Java/runtime/staging and
image scans are not rerun for this isolated CI policy change. The new native
full supply-chain gate and control regressions cover the changed behavior.

Full security report SHA-256:
`4bd781f80b5f7e8c58725c7a08e68a579b17951f9f570c6a32956328c8a5a2fc`.
Its source snapshot is dirty at HEAD066ceca and precedes the final row-cap test
and result-wording edits; a separate final source-only review scan is recorded
under `target/ci-safe/source-review-final.json`, not substituted for a full
Maven/image scan or clean committed release attestation. The actual release
preflight at that checkpoint advanced past security/runtime/staging and rejected the dirty,
uncommitted candidate: `source acceptance must match the committed release`.
Final artifact-bound load/failure/restore/native-runbook acceptance also remains
open. No task-DONE commit, push, merge, public release or Phase8 freeze is claimed.

The source review reproduces **10 findings at seven locations in five files**,
all from Gitleaks8.30.1's `generic-api-key` rule. Main-Java SAST finds zero issues
and verifies required source coverage. Duplicate report rows remain counted;
they are not silently deduplicated to satisfy policy. Only detector IDs, file
paths, line numbers and counts are retained in the ignored local metadata report
`target/ci-safe/secret-triage.json`; no match, credential, key or ciphertext is
exported. The snapshot includes842 tracked task files, not `.env`, local agent
instructions or ignored phase/index documents.

## Individual classification

Latest full-gate repetition (2026-10-18T03:17:45.482371Z) PASSes with new required
`acceptedSecretReviewExpiresAt=2026-11-16T18:22:02+00:00`,10 total/10 accepted/
zero unreviewed rows, zero SAST/advisory matches,235 Maven components and all
native scanner/dependency/unavailable-DB controls. Report SHA-256:
`b9a9166a4a4946ec5b995ae22a44a6dc80f18d2c3f7fc7bca9303e1aae80c4aa`.
215 offline controls pass on Windows/pinned Linux. This851-file working-tree
snapshot predates final result wording and is dirty at066ceca; actual preflight
correctly requires committed-source acceptance. After commit rerun the full
gate and check its actual HEAD/clean-state/expiry, not a manually patched report.
The approval's expiry/scope are unchanged, and no hosted CI is claimed.

Line numbers refer to the reviewed source snapshot, not permanent approvals.
Every item below is a false positive **for production-credential exposure at
that reviewed location**, based on its actual role. That conclusion does not
authorize deployment reuse of test material or assert that unreviewed local
configuration, Git history or external deployments contain no secrets.

| Reviewed location | Detector rows | Evidence and disposition |
|---|---:|---|
| [API contract](api-contract.md#12-refund), line637 | 1 | `Idempotency-Key` identifies a client request, not authentication. The documented request separately requires a merchant Bearer credential. Keep the valid example; no secret rotation is indicated by this finding. |
| [Production environment guard tests](../src/test/java/com/flowpay/backend/infrastructure/config/ProductionEnvironmentPostProcessorTest.java), lines139/159 | 4 | Two fixed synthetic AES configuration values are supplied to a `MockEnvironment` or a deliberately failing `EmptyConfiguration` startup. Tests exercise valid32-byte binding and sanitized invalid overrides, not live production access. Keep the fixtures and validation assertions. |
| [API-key domain test](../src/test/java/com/flowpay/backend/merchant/domain/ApiKeyTest.java), line14 | 1 | The value is the public lookup prefix, not the full API key. `SecureApiKeySecretCodec` requires43 secret characters after its format prefix, while lookup extraction retains only12. `ApiKeyAuthenticationService` also verifies the full key digest; the lookup prefix alone cannot authenticate. Keep this domain fixture. |
| [AES-GCM tests](../src/test/java/com/flowpay/backend/webhook/infrastructure/security/AesGcmWebhookSecretCipherTest.java), lines15/62 | 2 | Fixed synthetic32-byte keys test ciphertext round-trip,96-bit random IVs, tampering and wrong-key rejection. No external key service or deployed ciphertext is used. These are valid isolated test inputs, not evidence of a leaked production key. Keep their assertions and fail-closed behavior. |
| [Test profile](../src/test/resources/application-test.yaml), line28 | 2 | The synthetic key belongs to `src/test/resources`, activated for the `test` profile. The application requires an external encryption-key property, and the production guard rejects combined `prod`/`test` profiles. Test resources are not the packaged runtime configuration. Keep this test-only setup; never reuse it for deployment. |

Supporting implementation inspected directly:

- [API-key format, lookup extraction and digest verification](../src/main/java/com/flowpay/backend/merchant/infrastructure/security/SecureApiKeySecretCodec.java).
- [Authentication requires both a valid full key and its digest](../src/main/java/com/flowpay/backend/merchant/application/ApiKeyAuthenticationService.java).
- [AES-GCM invariants and authentication-tag failure](../src/main/java/com/flowpay/backend/webhook/infrastructure/security/AesGcmWebhookSecretCipher.java).
- [Mandatory Base64/32-byte key binding](../src/main/java/com/flowpay/backend/webhook/infrastructure/security/WebhookSecretProperties.java).
- [Production configuration and mixed-profile guard](../src/main/java/com/flowpay/backend/infrastructure/config/ProductionEnvironmentPostProcessor.java).

## Initial review decision (before bounded approval)

No finding justified a business-code correction or removal/rotation of a known
deployed credential. No fixture was rewritten, split, encoded, renamed or
generated dynamically merely to evade the detector. No detector, entropy bound,
scan directory, baseline, ignore comment, rule allowlist, exception, CI exit code
or severity threshold was changed. `ops/ci/exceptions.json` remains empty.

The [upstream pinned-version documentation](https://github.com/gitleaks/gitleaks/blob/v8.30.1/README.md#configuration)
describes configurable allowlists/ignores. Their availability does not authorize
their use here: recording these classifications as accepted false positives
would change the current zero-exception policy and needs a separate explicit
owner decision and fail-closed regression coverage. A folder-wide exclusion or
disabling `generic-api-key` would exceed the seven reviewed locations.

Under the initial constraints, a scanner that reports these valid fixtures and
a policy that rejects **every** finding cannot produce a green source gate.
Keep that rejection rather than manufacture green evidence. The next decision
is whether to keep the gate blocked or authorize narrowly scoped acceptance of
these exact reviewed false positives; no acceptance mechanism is implemented by
this review. Real/new/changed secret material must remain blocking in any future
design, and no `.env` exclusion policy is broadened by this document.

## Initial review verification boundary

Fresh source scan:10 secret-format findings, zero main-Java SAST findings,
required source coverage passed. Native built-in/custom secret canaries, all
seven SAST canaries and clean controls pass without scanner/config changes.
All183 focused offline controls, the22-link runbook/source-policy checks and
`git diff --check` pass. These are separate from the native source scan above.
The full Java suite and full Maven/image vulnerability scans are not repeated
for this documentation-only review; no build/runtime/test input is changed.
Earlier candidate results remain in the [dependency review](dependency-security-review.md),
with the same artifact-binding and final operational-evidence limitations.

No new long load/soak, failure, restore or native runbook acceptance, clean
committed release attestation, task-DONE commit, push, merge, account provisioning
or external deployment is claimed. Phase8 remains **IN PROGRESS/BLOCKED**.
