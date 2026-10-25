"""Run native UI state race tests on a real JVM without Android dependencies."""
from pathlib import Path
import os
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]


class AndroidProtocolTests(unittest.TestCase):
    def test_preview_races_and_reconnect_schedule(self):
        home = Path(os.environ.get('JAVA_HOME', r'C:\Program Files\Android\Android Studio\jbr'))
        javac = shutil.which('javac') or str(home / 'bin' / 'javac.exe')
        java = shutil.which('java') or str(home / 'bin' / 'java.exe')
        if not Path(javac).exists() or not Path(java).exists():
            self.skipTest('JDK unavailable; run the host-JVM UI protocol test with JDK 17+')
        with tempfile.TemporaryDirectory() as temp:
            files = [ROOT / 'app/src/main/java/com/n/watchremote/UiState.java',
                     ROOT / 'app/src/main/java/com/n/watchremote/BridgeNetworkConfig.java',
                     ROOT / 'tests/java/com/n/watchremote/UiStateTest.java',
                     ROOT / 'app/src/main/java/com/n/watchremote/ConversationState.java',
                     ROOT / 'app/src/main/java/com/n/watchremote/ConversationCache.java',
                     ROOT / 'tests/java/com/n/watchremote/ConversationStateTest.java',
                     ROOT / 'app/src/main/java/com/n/watchremote/AttentionLedger.java',
                     ROOT / 'tests/java/com/n/watchremote/AttentionLedgerTest.java',
                     ROOT / 'app/src/main/java/com/n/watchremote/HapticPolicy.java',
                     ROOT / 'tests/java/com/n/watchremote/HapticPolicyTest.java']
            built = subprocess.run([javac, '-encoding', 'UTF-8', '-d', temp, *map(str, files)],
                                   capture_output=True, text=True, encoding='utf-8', errors='replace')
            self.assertEqual(built.returncode, 0, built.stderr)
            run = subprocess.run([java, '-cp', temp, 'com.n.watchremote.UiStateTest'],
                                 capture_output=True, text=True, encoding='utf-8', errors='replace')
            self.assertEqual(run.returncode, 0, run.stderr)

            run = subprocess.run([java, '-cp', temp, 'com.n.watchremote.HapticPolicyTest'],
                                 capture_output=True, text=True, encoding='utf-8', errors='replace')
            self.assertEqual(run.returncode, 0, run.stderr)
            run = subprocess.run([java, '-cp', temp, 'com.n.watchremote.ConversationStateTest'],
                                 capture_output=True, text=True, encoding='utf-8', errors='replace')
            self.assertEqual(run.returncode, 0, run.stderr)

            run = subprocess.run([java, '-cp', temp, 'com.n.watchremote.AttentionLedgerTest'],
                                 capture_output=True, text=True, encoding='utf-8', errors='replace')
            self.assertEqual(run.returncode, 0, run.stderr)


if __name__ == '__main__':
    unittest.main()
