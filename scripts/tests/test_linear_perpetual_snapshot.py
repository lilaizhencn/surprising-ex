"""Exercise snapshot task outcomes without starting or changing a trading Core."""
from pathlib import Path
import os
import shutil
import subprocess
import tempfile
import unittest


class LinearPerpetualSnapshotTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="surprising-snapshot-task-")
        self.root = Path(self.temporary.name)
        self.bin = self.root / "bin"
        self.bin.mkdir()
        scripts = self.root / "scripts"
        scripts.mkdir()
        self.script = scripts / "linear-perpetual-snapshot.sh"
        shutil.copy2(Path(__file__).resolve().parents[1] / self.script.name, self.script)
        self.jar = self.root / "surprising-aeron-core/surprising-aeron-service/target/surprising-aeron-service.jar"
        self.jar.parent.mkdir(parents=True)
        self.jar.write_bytes(b"fixture; fake java below owns tool behavior")
        self.counter = self.root / "snapshots"
        self.counter.write_text("2")
        self.calls = self.root / "calls"
        self.env = os.environ.copy()
        self.env.update(PATH=str(self.bin) + os.pathsep + self.env["PATH"],
                        JAVA_HOME=str(self.root), RUNTIME_ROOT=str(self.root / "runtime"),
                        SNAPSHOT_FIXTURE=str(self.root), SNAPSHOT_MODE="success")
        self.executable("systemctl", "#!/usr/bin/env bash\n[[ ${SNAPSHOT_MODE} != inactive ]]\n")
        # The production task runs on Linux. Keep macOS fixtures independent of
        # GNU timeout installation; the Java fixture supplies its exit status.
        self.executable("timeout", '#!/usr/bin/env bash\nshift\nexec "$@"\n')
        self.executable("java", """#!/usr/bin/env python3
import os,sys
from pathlib import Path
root=Path(os.environ['SNAPSHOT_FIXTURE']);mode=os.environ['SNAPSHOT_MODE'];action=sys.argv[-1]
with (root/'calls').open('a') as out:out.write(action+'\\n')
if action=='is-leader':
 if mode=='follower':sys.exit(1)
 if mode=='classpath':print('Error: Could not find or load main class io.aeron.cluster.ClusterTool',file=sys.stderr);sys.exit(1)
 if mode=='timeout':sys.exit(124)
elif action=='recording-log':
 if mode=='recording-error':print('recording log unavailable',file=sys.stderr);sys.exit(2)
 for i in range(int((root/'snapshots').read_text())):print('type=SNAPSHOT, isValid=true')
elif action=='snapshot':
 if mode=='request-error':sys.exit(2)
 (root/'snapshots').write_text(str(int((root/'snapshots').read_text())+2))
else:sys.exit(3)
""")

    def executable(self, name, text):
        path = self.bin / name
        path.write_text(text)
        path.chmod(0o700)

    def tearDown(self):
        self.temporary.cleanup()

    def run_task(self, mode):
        self.env["SNAPSHOT_MODE"] = mode
        return subprocess.run(["bash", str(self.script)], env=self.env, text=True,
                              stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=10)

    def test_completed_requires_two_new_valid_records(self):
        result = self.run_task("success")
        self.assertEqual(result.returncode, 0, result.stdout)
        self.assertIn("SNAPSHOT=COMPLETE validRecordsBefore=2 validRecordsAfter=4", result.stdout)
        self.assertEqual(self.calls.read_text().splitlines(), ["is-leader", "recording-log", "snapshot", "recording-log"])

    def test_healthy_follower_skips_without_request(self):
        result = self.run_task("follower")
        self.assertEqual(result.returncode, 0, result.stdout)
        self.assertIn("reason=node-not-leader", result.stdout)
        self.assertEqual(self.calls.read_text().splitlines(), ["is-leader"])

    def test_inactive_stack_skips_without_loading_java(self):
        result = self.run_task("inactive")
        self.assertEqual(result.returncode, 0, result.stdout)
        self.assertIn("reason=stack-not-active", result.stdout)
        self.assertFalse(self.calls.exists())

    def test_missing_jar_is_failure(self):
        self.jar.unlink()
        result = self.run_task("success")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("reason=core-jar-unavailable", result.stdout)
        self.assertFalse(self.calls.exists())

    def test_classpath_error_cannot_masquerade_as_follower(self):
        result = self.run_task("classpath")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("reason=leader-check-failed exitCode=1\n", result.stdout)
        self.assertIn("Could not find or load main class", result.stdout)
        self.assertNotIn("SNAPSHOT=SKIPPED", result.stdout)

    def test_timeout_is_failure(self):
        result = self.run_task("timeout")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("reason=leader-check-failed exitCode=124", result.stdout)

    def test_recording_log_failure_cannot_become_zero_count(self):
        result = self.run_task("recording-error")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("reason=recording-log-unavailable", result.stdout)
        self.assertNotIn("SNAPSHOT=COMPLETE", result.stdout)

    def test_snapshot_request_failure_is_reported(self):
        result = self.run_task("request-error")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("reason=snapshot-request-failed", result.stdout)
        self.assertNotIn("SNAPSHOT=COMPLETE", result.stdout)


if __name__ == "__main__":
    unittest.main()
