import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch
from verify import Fixture


class FixtureOwnershipTest(unittest.TestCase):
    def test_existing_namespace_is_not_deleted_when_start_is_refused(self):
        with tempfile.TemporaryDirectory() as directory:
            fixture = Fixture(Path(directory) / 'case')
            with patch('verify.run', return_value='afqa0 (id: 1)\n'), patch('verify.subprocess.run') as commands:
                with self.assertRaisesRegex(AssertionError, 'already exist'):
                    fixture.network()
                fixture.cleanup()
                commands.assert_not_called()

    def test_existing_bridge_is_not_deleted_when_creation_fails(self):
        with tempfile.TemporaryDirectory() as directory:
            fixture = Fixture(Path(directory) / 'case')
            with patch('verify.run', side_effect=['', subprocess.CalledProcessError(2, 'ip')]), \
                    patch('verify.subprocess.run') as commands:
                with self.assertRaises(subprocess.CalledProcessError):
                    fixture.network()
                fixture.cleanup()
                commands.assert_not_called()


if __name__ == '__main__':
    unittest.main()
