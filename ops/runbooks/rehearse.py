"""Owned positive read-only command rehearsal; not a production mutation tool."""
import datetime as dt
import importlib.util
import json
import os
from pathlib import Path
import sys
import tempfile

SPEC = importlib.util.spec_from_file_location('runbooks', Path(__file__).with_name('runbooks.py'))
ops = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(ops)
recovery, gate, ROOT = ops.recovery, ops.gate, ops.ROOT


def run():
    digest = ops.executable_digest()
    report = {'status': 'incomplete', 'timestamp': dt.datetime.now(dt.timezone.utc).isoformat(), 'workloadSha256': digest}
    path = gate.SAFE / 'runbooks.json'
    path.unlink(missing_ok=True)
    previous = {key: os.environ.get(key) for key in recovery.ENV_KEYS}
    try:
        report['contracts'] = ops.check()
        identity = recovery.staging.image.image_identity()
        gate.require(identity == gate.document(ROOT / 'ops/backup/policy.json')['applicationImage'], 'rehearsal requires unchanged verified T13 artifact')
        gate.require(not gate.run(['docker', 'ps', '--quiet'])[0].strip(), 'active unrelated workloads; no adoption or stop allowed')
        with tempfile.TemporaryDirectory(prefix='flowpay-runbooks-') as directory:
            context = recovery.Recovery(report, Path(directory), identity)
            context.mode = 'runbooks'
            context.command += ['--file', str(ops.HERE / 'fixture.yaml')]
            context.activate(consumers=True, http=True)
            recovery.staging.assert_empty_project(context.project)
            try:
                context.phase('owned infrastructure and verified application')
                context.generate()
                context.validate()
                context.infrastructure()
                context.application(True, True)
                context.probe_json('setup')
                context.probe_json('workflow', 'rehearsal')
                context.drain()
                context.phase('read-only operator commands and rejected non-DEAD retry')
                before = context.snapshot()
                raw, _ = gate.run([sys.executable, '-B', str(ops.HERE / 'runbooks.py'), 'triage', '--project', context.project], timeout=120)
                projection = json.loads(raw)
                gate.require(projection['durable']['status'] == projection['broker']['status'] == 'captured' and
                             projection['durable']['counts']['payments'] == 1 and projection['durable']['counts']['postings'] == 2 and
                             all(v['readyz'] == 'UP' and v['metrics']['status'] == 'captured' for v in projection['replicas']),
                             'native operator triage projections unavailable or incorrect')
                report['apiDiagnosis'] = context.probe_json('delivery-triage')
                context.phase('full persisted snapshot comparison')
                after = context.snapshot()
                report['snapshotComparison'] = {key: before[key] == after[key] for key in before}
                report['changedTableNames'] = [key for key in recovery.TABLES if before['tables'][key] != after['tables'][key]]
                gate.require(before == after, 'documented diagnosis or rejected retry changed persisted history')
                context.phase('whole-project financial HMAC and trace audit')
                context.recovery_audit(1)
                report.update(imageId=identity, jarSha256=gate.sha256(recovery.staging.image.verified_jar()),
                              durableCounts=projection['durable']['counts'], queueCounts=projection['broker']['queues'],
                              readOnlyCommandsAndSnapshotsUnchanged=True)
                context.phase('fail-closed release preflight; not a release authorization')
                try:
                    recovery.staging.release_check({})
                except gate.GateError:
                    report['releasePreflight'] = 'BLOCKED; required security/current evidence not passed'
                else:
                    raise gate.GateError('unexpected release clearance; review independent security gates')
            finally:
                context.cleanup()
        gate.require(ops.executable_digest() == digest, 'runbook commands changed during rehearsal')
        report.update(status='passed', completedAt=dt.datetime.now(dt.timezone.utc).isoformat())
    except gate.GateError as error:
        report.update(status='failed', reason=str(error))
    except Exception as error:
        kind = type(error).__name__
        kind = kind if kind in ('KeyError', 'TypeError', 'ValueError', 'FileNotFoundError') else 'internal error'
        report.update(status='failed', reason='owned runbook rehearsal failed (' + kind + '); raw diagnostic output withheld')
    finally:
        for key, value in previous.items():
            if value is None:
                os.environ.pop(key, None)
            else:
                os.environ[key] = value
        gate.write_json(path, report)
    gate.require(report['status'] == 'passed', 'owned runbook rehearsal failed; see safe evidence')
    return report
