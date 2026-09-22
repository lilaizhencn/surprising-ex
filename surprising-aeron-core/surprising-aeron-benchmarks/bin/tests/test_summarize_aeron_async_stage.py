import contextlib
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

SCRIPT = Path(__file__).resolve().parents[1] / 'summarize-aeron-async-stage.py'
spec = importlib.util.spec_from_file_location('summary', SCRIPT)
summary = importlib.util.module_from_spec(spec)
spec.loader.exec_module(summary)


class SaturationReportTest(unittest.TestCase):
    def report(self, stage='end_to_end', *, passed=True, blocked=50_000_000_000,
               peak=256, cpu=True, lane_execution=24_000_000_000):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            lines = [
                'measurementStartEpochMillis=1790051081945',
                'measurementEndEpochMillis=1790051141945',
                f'steadyCapacity elapsedSeconds=60 businessOpsPerSec=350000 coreMessagesPerSec=33000 peakInFlight={peak} windowBlockedNanos={blocked}',
                'pipelineHighWater matcher=256 completion=256 context=256 lanes=[64,64,64,64]',
                f'laneWork lane=0 operation=0 completed=1000 executionNanos={lane_execution} measuredNanos=60000000000',
                'mixedCapacity=PASS' if passed else 'mixedCapacity=FAIL',
            ]
            (root / 'client.log').write_text('\n'.join(lines))
            jfr = {'samples': 30 if cpu else 0, 'logicalCpus': 16, 'threads': {
                name: {'singleCorePercent': 99.0}
                for name in ['trading-owner-0', 'core-matcher-0', 'core-account-lane-0']
            } if cpu else {}}
            with patch.object(summary, 'parse_jfr', return_value=jfr), patch(
                'sys.argv', [str(SCRIPT), directory, '--stage', stage, '--window', '256']
            ), contextlib.redirect_stdout(io.StringIO()):
                summary.main()
            return json.loads((root / 'metrics.json').read_text())

    def test_busy_spin_and_peak_queue_do_not_prove_any_stage_saturated(self):
        for stage in ['owner', 'matcher', 'lane', 'end_to_end']:
            with self.subTest(stage=stage):
                report = self.report(stage, lane_execution=59_000_000_000)
                self.assertEqual('UNCONFIRMED', report['saturationGate']['status'])
                self.assertNotIn('thresholds', report['saturationGate'])
                self.assertTrue(report['loadEvidence']['windowReached'])
                self.assertAlmostEqual(5 / 6, report['loadEvidence']['windowBlockedTimeRatio'])
                self.assertEqual('HIGH_WATER_ONLY', report['loadEvidence']['queueEvidence'])

    def test_low_observed_load_does_not_prove_no_bottleneck(self):
        report = self.report(blocked=0, peak=20)
        self.assertEqual('UNCONFIRMED', report['saturationGate']['status'])
        self.assertFalse(report['loadEvidence']['windowReached'])
        self.assertFalse(report['loadEvidence']['windowBackpressureObserved'])
        self.assertEqual(0, report['loadEvidence']['windowBlockedTimeRatio'])

    def test_missing_cpu_remains_unknown_not_zero(self):
        report = self.report(cpu=False)
        self.assertIsNone(report['cpu']['ownerSingleCorePercent'])
        self.assertIn('thread-cpu-evidence-missing', report['saturationGate']['reasons'])
        self.assertEqual('UNCONFIRMED', report['saturationGate']['status'])

    def test_functional_failure_invalidates_assessment(self):
        self.assertEqual('INVALID', self.report(passed=False)['saturationGate']['status'])

    def test_invalid_blocked_duration_is_not_clamped_to_saturation(self):
        report = self.report(blocked=61_000_000_000)
        self.assertIsNone(report['loadEvidence']['windowBlockedTimeRatio'])
        self.assertFalse(report['loadEvidence']['windowBlockedTimeValid'])

    def test_lane_interval_is_labelled_wall_time(self):
        report = self.report()
        self.assertEqual(.4, report['laneExecutionWallRatioAvg'])
        self.assertEqual(24_000_000_000, report['laneWork']['0']['executionWallNanos'])
        self.assertNotIn('laneUsefulExecutionRatioAvg', report)

    def test_missing_input_is_invalid_and_missing_evidence_is_explicit(self):
        with tempfile.TemporaryDirectory() as directory, patch('sys.argv', [
            str(SCRIPT), directory, '--stage', 'end_to_end', '--window', '256'
        ]), contextlib.redirect_stdout(io.StringIO()):
            summary.main()
            report = json.loads((Path(directory) / 'metrics.json').read_text())
        self.assertEqual('INVALID', report['saturationGate']['status'])
        self.assertIsNone(report['loadEvidence']['windowReached'])
        self.assertIsNone(report['loadEvidence']['windowBackpressureObserved'])
        self.assertEqual('MISSING', report['loadEvidence']['queueEvidence'])


if __name__ == '__main__':
    unittest.main()
