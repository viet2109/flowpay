# FlowPay release checklist (P8-T17; P8-T18 exit audit)

## Decision and ownership

**Current decision: NO-GO for retained/external deployment.** A simulator lab
passing operational checks is not a public API deployment, real-money readiness
or phase completion. The repository owner must name the exact environment,
incident/release approver, access route and rollback/cutover authority before
approving any release. No provider/domain/registry/paid account is provisioned;
no frontend or admin console is implied. P8-T18 is DONE/FROZEN as of2026-10-18
for the disposable Linux/container simulator scope after final local and hosted
gates pass. This is not satisfaction of the independent external-release gates below.

See the [T18 exit audit](production-exit-audit.md) for the current candidate,
clean committed-source requirement and final-artifact operational results. The
owner-approved [dependency/base-image review](dependency-security-review.md)
records the new full Java/build/image-security/runtime passes, zero Maven
advisory matches. Later owner-approved bounded source review yields a full
security PASS with10 total/10 accepted/zero unreviewed findings, not a real-secret
waiver or clean committed release attestation. It
also retains the rejected Grafana staging check and separate diagnostic result;
no old operational acceptance or clean release is inferred.

This checklist records required decisions, not blanket authorization to execute
mutations. Keep source/image evidence current and attributable to the actual
candidate. Never tick a box because an older report exists or a tool was absent.

## Mandatory go or no-go gates

The unchecked decisions below apply to a future retained/external target and
its then-current artifact/evidence, not outstanding Phase8 lab tasks. Final
accepted lab evidence is recorded in the exit audit; it does not select a
production environment or authorize deployment.

- [ ] Owner selects/provisions the retained target, trusted TLS/DNS/ingress and
  restricted operator access. No public management/DB/broker listeners or
  undocumented0.0.0.0 bind; review IPv4/IPv6 firewall/egress and all service images.
- [ ] Current committed candidate passes `python -B ops/ci/gate.py verify` with
  JDK21 and no skipped required PostgreSQL/RabbitMQ/architecture suites. T18
  owns the final clean gate; a historical T13 report is not that new gate.
- [ ] Exact verified JAR is built by `python -B ops/container/image.py build`;
  source-input/JAR/image identities and resource/config/key references agree.
- [ ] Independent source security and application-image gates pass:
  `python -B ops/ci/gate.py security` and
  `python -B ops/container/image.py scan`. Currently blocked findings are not
  waived. Dependency/SAST exceptions remain empty; source false-positive
  acceptance requires the owner's exact, unexpired
  [review manifest](secret-findings-review.md#approved-bounded-false-positive-acceptance).
  No real-secret waiver, fixture rewrite, detector or image-secret suppression
  is authorized. Recheck expiry on the actual release candidate.
- [ ] Runtime and staging pass on that exact image:
  `python -B ops/container/image.py runtime` and
  `python -B ops/staging/staging.py smoke`. Require both fresh and Phase7 upgrade,
  forward V001–V013 compatibility, key/HMAC/Idempotency and financial history.
- [ ] Review DB migration/owner/runtime privilege separation. T13 uses an
  official-image bootstrap superuser for runtime/Flyway; least privilege is not
  established. Pinning infrastructure is not a clean scan of every service image.
- [ ] Measured capacity/load/soak, failure/restart and DR evidence match the
  intended artifact/config/host. T14 targets are frozen, not universal SLOs;
  T15 shares one host and records rolling transients. Recalibrate/reapprove on
  a materially different host/workload; do not change old approvals to green.
- [ ] Operator provisions and restore-tests independent protected off-host
  data/key storage, separate recovery keys/catalog, daily schedule/age/failure
  alerts and7 daily/4 weekly retention. Actual backup RPO must meet the approved
  target. T16's disposable archives were deleted, not retained recovery copies.
- [ ] External monitoring/notification routing, independent monitor availability,
  staffed response and disk/retention/resource budgets are explicitly arranged
  and tested. The current Prometheus rules have no external paging route.
- [ ] TLS, AES/JWT, DB/broker and API-key ownership/continuity are confirmed;
  incident containment and coordinated rotation are reviewed without losing
  historical decryptability. No automatic AES migration, dual-secret Webhook
  grace, global session revocation or seamless JWT overlap is demonstrated.
- [ ] Owner signs the remaining simulator-only limits and the exact compatible
  rollback/restore/cutover plan, then schedules supervised rollout and abort
  criteria. No financial-history repair or provider reconciliation is included.

Run the final fail-closed preflight only after independent gates:

```powershell
python -B ops/staging/staging.py release-check
```

This existing command requires passed source/image/runtime/staging evidence,
matching immutable artifact, both staging cases, evidence age<=72h and current
clean committed source identity. Missing/stale/future/mismatched/dirty evidence
blocks; a functional DR report does not replace it. Preflight is necessary, not
sufficient: it does **not** cover all decisions above, infrastructure-image
security, hosted CI/branch protection, notification delivery or external rollout.
T17 rehearses the expected block; it must not mock success or add a bypass.

## Approved rollout and abort procedure

1. Owner records exact change, environment/resource identities, known-good image,
   config/key references, backup checkpoint, migration compatibility and decision
   makers. Apply the [authority classes](operations-runbook.md#scope-and-authority).
2. Fence intake and drain or gracefully quiesce approved resources. Preserve
   financial/event/attempt history and the encrypted restorable checkpoint.
3. Roll the approved compatible candidate under supervision. Keep the tested
  120s graceful-stop allowance; do not SIGKILL for routine rollout or assume
   Docker running means readiness. Never down-migrate schema automatically.
4. Require actual readiness, private listener isolation, auth/key continuity,
   Idempotency replay, consumer/financial/HMAC and fresh telemetry checks. Resume
   intake only with owner approval and observe backlog/drain against reviewed
   resource/error/latency thresholds.
5. Stop rollout/fence intake on unexplained financial mismatch, incorrect key,
   migration failure, malformed/contradictory events, uncontrolled DLQ/DEAD growth,
   unavailable trustworthy telemetry, resource exhaustion or unexpected errors.
   Do not delete evidence, raise thresholds, reset PUBLISHED or assume UNKNOWN
   is failed. Use [incident procedures](operations-runbook.md), not ad-hoc SQL.
6. After compatibility review, revert application/config only. T13's demonstrated
   rollback was to the same image after bad JWT configuration, not a historical
   binary. Otherwise escalate for a forward fix or approved isolated restore and
   data-loss decision. Record all transients/duplicates and recipient impact.

## Evidence and residual risk

This ledger distinguishes the completed local evidence from unmet release
conditions. Refer to the linked runbooks for exact workload/report hashes and
safe artifacts; reports in `target/ci-safe` are not committed secret archives.

| Gate | Actual evidence | Limit or unresolved gate |
|---|---|---|
| T08/T13 monitoring/staging | Private dashboards, rules, TLS/dual-stack/proxy and fresh/legacy V013 gates | No external operator route/paging, full-host HA or least-privilege runtime role acceptance |
| T18 source/image security | Approved remediation:235 Maven components/zero advisories;301 image components/zero HIGH/CRITICAL/UNKNOWN;10 exact source false positives accepted with exclusive expiry | Final source-expiry metadata/clean-commit attestation required; not a scan of all infrastructure or waiver for real secrets |
| T18 candidate artifact | JAR `3212f15b52fb36ef9c967cb05e49b8bf897b822c0fa19b7424225f1c657be0d7`; image `sha256:951f5e3b2cc9124eb35af64c9969a8a3999884a9b05484e9c79e533ab6bbb993`; fresh1,407 tests/196 suites | Build/test inputs remain exact; prior T13 artifact/results stay historical |
| T18 [load repetition](load-testing.md) | Independent30-minute soak; regular route tails meet500/1000ms, zero regular drops/429; stress13 drops/96 policy429 | Declared simulator/host only; no maximum throughput, alert-notification timeline or production SLO |
| T18 [failure repetition](operational-drills.md) | All eight two-instance cases; worker/stale recovery39.69/39.66s; rolling270 successes/7 transients | Single host, no zero-error rollout or provider crash reconciliation |
| T18 [restore repetition](backup-restore.md) | Cold/mismatch101.40/110.38s;19-table/sequence/schema/key/HMAC/trace/history audits | RPO target24h/RTO lab15m; no off-host schedule/retention/PITR or standing real-queue replay approval |
| T18 [runbook repetition](operations-runbook.md) |215 Windows/Linux controls;25 links/source-contract checks; native read-only SQL/queue/health/metrics/dashboard history/409; exact snapshots unchanged; preflight records BLOCKED | No newly rehearsed DEAD202/exhaustion, real remediation/rotation/DLQ replay or deployment authority |
| T18 [exit audit](production-exit-audit.md) | Exact local artifact gates, clean canonical source/preflight and both hosted CI jobs PASS at43ae0f3;228 Windows/Linux controls; rejected attempts retained | DONE/FROZEN lab scope only; hosted rebuild is not a reapproval of performance-artifact identity; retained/public release stays NO-GO |

The T17 command/link/privacy rehearsal is separate evidence of actionable
diagnosis, not completion of these release gates. Phase8 is not declared DONE
until T18 audits its required outcomes and residual risks. Even phase completion
would not authorize push/merge, public deployment, purchases or real-money use.

## Current operator reproduction

```powershell
python -B ops/runbooks/runbooks.py check
python -B ops/runbooks/runbooks.py rehearse
```

The native rehearsal is bound to the active reviewed recovery policy, not a
floating image. T18 preserved the original T13/T16 declarations and honestly
rebound the active policy through the new accepted load/failure/restore report
chain. Its new-image rehearsal now passes with full persisted snapshots unchanged.
Future image changes still require their own reviewed real evidence chain, never
copied passes or rewritten historical approvals. See the exit audit above.

Follow [rehearsal scope and evidence](operations-runbook.md#rehearsal-and-evidence).
These commands never deploy or bypass security. The final native rehearsal
passed again on2026-10-18 with owned cleanup; the earlier2026-10-17 result stays
historical. Artifact/command/report hashes and limits are in the linked runbook.
Hosted gates remain separately observable by run/commit, not inferred from this
rehearsal. External deployment prerequisites and authority remain NO-GO even
after a successful phase freeze.
