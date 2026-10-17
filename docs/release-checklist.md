# FlowPay release checklist (P8-T17)

## Decision and ownership

**Current decision: NO-GO for retained/external deployment.** A simulator lab
passing operational checks is not a public API deployment, real-money readiness
or phase completion. The repository owner must name the exact environment,
incident/release approver, access route and rollback/cutover authority before
approving any release. No provider/domain/registry/paid account is provisioned;
no frontend or admin console is implied. T18's final gates remain unstarted.

This checklist records required decisions, not blanket authorization to execute
mutations. Keep source/image evidence current and attributable to the actual
candidate. Never tick a box because an older report exists or a tool was absent.

## Mandatory go or no-go gates

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
  waived. Exceptions remain empty; no fixture rewrite/suppression is authorized.
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
| T11/T12 security |10 source secret-format findings; app-image6 CRITICAL/12 HIGH remain unwaived | NO-GO; historical counts are not a new scan of the current release or all infrastructure |
| T13 artifact | JAR `32874cf74c358f37137f9c88d3d7f613f85f146a1628f9604b45fcf38a6f8dc7`; image `sha256:a5c4c7ecbe6d888edad4eda95db786330a7ae412c1daf38470549165241f6275`;1,407 tests/196 suites | Reused unchanged artifact, not T18's final source/security gate |
| T14 [load](load-testing.md) |30-minute soak; regular route tails meet500/1000ms; stress112 policy429 | Frozen simulator/host; no universal throughput, alert-notification timeline or production SLO |
| T15 [failure](operational-drills.md) | Eight two-instance cases; worker/stale recovery41.20/39.18s; rolling230 successes/6 transients | Single host, no zero-error rollout or provider crash reconciliation |
| T16 [restore](backup-restore.md) | Cold/mismatch101.28/109.23s;19-table/sequence/schema/key/HMAC/trace/history audits | RPO target24h/RTO lab15m; no off-host schedule/retention/PITR or standing real-queue replay approval |
| T17 [runbooks](operations-runbook.md#rehearsal-and-evidence) |178 Windows/Linux controls;19 final links/anchors/source-config checks; native read-only SQL/queue/health/metrics and dashboard history/409; exact persisted snapshots unchanged; preflight BLOCKED | No newly rehearsed DEAD202/exhaustion, real remediation/rotation/DLQ replay or deployment authority |

The T17 command/link/privacy rehearsal is separate evidence of actionable
diagnosis, not completion of these release gates. Phase8 is not declared DONE
until T18 audits its required outcomes and residual risks. Even phase completion
would not authorize push/merge, public deployment, purchases or real-money use.

## Current operator reproduction

```powershell
python -B ops/runbooks/runbooks.py check
python -B ops/runbooks/runbooks.py rehearse
```

Follow [rehearsal scope and evidence](operations-runbook.md#rehearsal-and-evidence).
These commands never deploy or bypass security. The final native rehearsal
passed on2026-10-17 with owned cleanup; its artifact/command/report hashes and
limits are recorded in the linked runbook. Unperformed hosted/external steps
and T18 remain open; security and deployment prerequisites are still NO-GO.
