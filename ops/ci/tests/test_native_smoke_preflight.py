"""Offline shell negatives: fake Docker/HTTP, before any fixture creation."""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[3]
# The curl function only satisfies an early command-v prerequisite; any actual
# invocation is forbidden. Minimal Linux control images need no curl package.
WRAPPER = r"""
docker() {
  if test "$1" = ps; then kind=container; else kind=$1; fi
  printf 'PROBE_%s\n' "$kind" >&2
  case "$kind" in container|network|volume) ;; *) return 97 ;; esac
  if test "$kind" = "$PROBE_RESOURCE"; then
    if test "$PROBE_MODE" = exists; then printf 'synthetic-existing-id\n'; return 0; fi
    return 41
  fi
  return 0
}
curl() { printf 'PROBE_curl\n' >&2; return 97; }
export -f docker curl
bash "$1"
"""


class NativeSmokePreflightTest(unittest.TestCase):
    def test_existing_resources_and_backend_errors_stop_before_creation(self):
        if os.name == 'nt':
            git = shutil.which('git')
            candidates = [Path(git).parent.parent / 'bin/bash.exe'] if git else []
            candidates.append(Path('C:/Program Files/Git/bin/bash.exe'))
            bash = next((path for path in candidates if path.is_file()), None)
        else:
            command = shutil.which('bash')
            bash = Path(command) if command else None
        self.assertIsNotNone(bash, 'The supported POSIX/Git Bash shell is required for native script controls')
        with tempfile.TemporaryDirectory(prefix='flowpay-shell-negative-') as directory:
            work = Path(directory)
            jar = work / 'empty.jar'
            jar.touch()
            environment = os.environ.copy()
            environment.pop('BASH_ENV', None)
            environment.pop('ENV', None)
            environment.update(FLOWPAY_JAR=jar.as_posix(), FLOWPAY_TEST_CLASSES=work.as_posix(),
                               TMPDIR=work.as_posix())
            bins = [bash.parent, bash.parent.parent / 'usr/bin']
            environment['PATH'] = os.pathsep.join(path.as_posix() for path in bins if path.is_dir()) \
                + os.pathsep + environment.get('PATH', '')
            for script in ('ops/monitoring/tests/smoke.sh', 'ops/egress/tests/smoke.sh',
                           'ops/ingress/tests/application-smoke.sh'):
                for resource in ('container', 'network', 'volume'):
                    for mode in ('exists', 'backend_failure'):
                        with self.subTest(script=script, resource=resource, mode=mode):
                            environment.update(PROBE_RESOURCE=resource, PROBE_MODE=mode)
                            result = subprocess.run(
                                [str(bash), '--noprofile', '--norc', '-c', WRAPPER, '--', script],
                                cwd=ROOT, env=environment, capture_output=True, timeout=15, check=False)
                            self.assertNotEqual(result.returncode, 0)
                            self.assertIn(('PROBE_' + resource).encode(), result.stderr)
                            self.assertFalse(any(('PROBE_' + command).encode() in result.stderr
                                                 for command in ('compose', 'run', 'rm', 'curl')))
                            self.assertEqual({path.name for path in work.iterdir()}, {'empty.jar'})
