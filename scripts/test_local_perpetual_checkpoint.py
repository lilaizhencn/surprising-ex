import importlib.util
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import MagicMock, patch

spec = importlib.util.spec_from_file_location("checkpoint", Path(__file__).with_name("checkpoint-local-perpetual.py"))
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


def entry(timestamp, position, service, valid="true", term=1):
    return (f"Entry{{recordingId=1, leadershipTermId={term}, timestamp={timestamp}, logPosition={position}, "
            f"serviceId={service}, type=SNAPSHOT, isValid={valid}}}")


class CheckpointTest(unittest.TestCase):
    def test_requires_matching_valid_consensus_and_service_snapshots(self):
        log = entry(100, 42, 0) + entry(100, 42, -1)
        log += entry(200, 55, 0) + entry(200, 55, -1, "false")
        log += entry(300, 66, 0) + entry(300, 67, -1)
        log += entry(400, 70, 0, term=1) + entry(400, 70, -1, term=2)
        self.assertEqual((100, 42), module.latest_complete_snapshot(log))
        self.assertEqual((0, 0), module.latest_complete_snapshot(entry(100, 42, 0)))

    @patch.object(module.shutil, "disk_usage", return_value=SimpleNamespace(free=10 * 1024**3))
    @patch.object(module.urllib.request, "urlopen")
    @patch.object(module.subprocess, "run")
    def test_recent_snapshot_does_not_query_or_trigger(self, run, urlopen, disk):
        timestamp = int(module.time.time() * 1000)
        run.return_value = SimpleNamespace(returncode=0, stdout=entry(timestamp, 42, 0) + entry(timestamp, 42, -1))
        self.assertEqual("RECENT", module.checkpoint(Path("/tmp"), Path("java"), Path("tools.jar"), 300)[0])
        urlopen.assert_not_called()
        self.assertEqual(["recording-log"], [call.args[0][-1] for call in run.call_args_list])

    @patch.object(module.shutil, "disk_usage", return_value=SimpleNamespace(free=10 * 1024**3))
    @patch.object(module.urllib.request, "urlopen", side_effect=TimeoutError("Core recovering"))
    @patch.object(module.subprocess, "run", return_value=SimpleNamespace(returncode=0, stdout=""))
    def test_recovering_core_is_not_interrupted(self, run, urlopen, disk):
        with self.assertRaises(TimeoutError):
            module.checkpoint(Path("/tmp"), Path("java"), Path("tools.jar"), 300)
        self.assertEqual(["recording-log"], [call.args[0][-1] for call in run.call_args_list])

    @patch.object(module.shutil, "disk_usage", return_value=SimpleNamespace(free=10 * 1024**3))
    @patch.object(module.urllib.request, "urlopen")
    @patch.object(module.subprocess, "run")
    def test_checks_completion_after_request(self, run, urlopen, disk):
        response = MagicMock()
        response.read.return_value = b'{"bids":[],"asks":[]}'
        urlopen.return_value.__enter__.return_value = response
        run.side_effect = [SimpleNamespace(returncode=0, stdout=out) for out in
                           ["", "", "snapshot requested", entry(1000, 50, 0) + entry(1000, 50, -1)]]
        self.assertEqual(("COMPLETED", (1000, 50)), module.checkpoint(Path("/tmp"), Path("java"), Path("tools.jar"), 300))
        self.assertEqual(["recording-log", "is-leader", "snapshot", "recording-log"],
                         [call.args[0][-1] for call in run.call_args_list])

    @patch.object(module.shutil, "disk_usage", return_value=SimpleNamespace(free=1000))
    @patch.object(module.subprocess, "run")
    def test_low_disk_does_not_start_snapshot(self, run, disk):
        with self.assertRaisesRegex(RuntimeError, "disk space"):
            module.checkpoint(Path("/tmp"), Path("java"), Path("tools.jar"), 300)
        run.assert_not_called()


if __name__ == "__main__":
    unittest.main()
