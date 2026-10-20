import subprocess
import unittest
from unittest.mock import Mock, patch

from record_manager import RecordManager


class RecordManagerTests(unittest.TestCase):
    def test_execution_resume_and_permission_methods_are_rejected_before_transport(self):
        manager = RecordManager()
        with patch.object(manager, '_call') as transport:
            for method in ('turn/start', 'turn/steer', 'thread/resume', 'thread/shellCommand',
                    'item/commandExecution/requestApproval'):
                with self.assertRaises(ValueError):
                    manager.call(method, {})
            transport.assert_not_called()
            manager.call('thread/read', {'threadId': 'record1', 'includeTurns': False})
            transport.assert_called_once_with('thread/read', {'threadId': 'record1', 'includeTurns': False})

    def test_shutdown_waits_for_exit_and_only_terminates_its_own_child_if_needed(self):
        manager = RecordManager()
        proc = manager.proc = Mock()
        proc.wait.side_effect = [subprocess.TimeoutExpired('record-manager', 3), 0]
        proc.poll.return_value = 0
        manager.close()
        proc.stdin.close.assert_called_once()
        self.assertEqual(proc.wait.call_count, 2)
        proc.terminate.assert_called_once()
        proc.stdout.close.assert_called_once()

    def test_normal_exit_needs_no_termination(self):
        manager = RecordManager()
        proc = manager.proc = Mock()
        proc.poll.return_value = 0
        manager.close()
        proc.wait.assert_called_once_with(timeout=3)
        proc.terminate.assert_not_called()


if __name__ == '__main__':
    unittest.main()
