"""Isolated host-JVM completion policy tests; no Android/Gradle or devices."""
from pathlib import Path
import os
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]

class CompletionClientTests(unittest.TestCase):
    def test_completion_cursor(self):
        home = Path(os.environ.get('JAVA_HOME', r'C:\Program Files\Android\Android Studio\jbr'))
        javac = shutil.which('javac') or str(home / 'bin/javac.exe')
        java = shutil.which('java') or str(home / 'bin/java.exe')
        if not Path(javac).exists() or not Path(java).exists():
            self.skipTest('JDK unavailable')
        files = [ROOT / ('tests/java/com/n/watchremote/' + name + '.java') for name in ('CompletionCursorTest', 'CompletionSettingBoundaryTest')]
        source = ROOT / 'app/src/main/java/com/n/watchremote/CompletionCursor.java'
        if source.exists():
            files.append(source)
        files.extend(ROOT / ('app/src/main/java/com/n/watchremote/' + name + '.java') for name in ('HapticPolicy', 'ConversationState'))
        with tempfile.TemporaryDirectory(prefix='completion-client-') as temp:
            built = subprocess.run([javac, '-encoding', 'UTF-8', '-d', temp, *map(str, files)], capture_output=True, text=True, encoding='utf-8', errors='replace', timeout=60)
            self.assertEqual(built.returncode, 0, built.stderr)
            run = subprocess.run([java, '-XX:ActiveProcessorCount=2', '-cp', temp, 'com.n.watchremote.CompletionCursorTest'], capture_output=True, text=True, encoding='utf-8', errors='replace', timeout=30)
            self.assertEqual(run.returncode, 0, run.stderr)
            run = subprocess.run([java, '-XX:ActiveProcessorCount=2', '-cp', temp, 'com.n.watchremote.CompletionSettingBoundaryTest', str(ROOT)], capture_output=True, text=True, encoding='utf-8', errors='replace', timeout=30)
            self.assertEqual(run.returncode, 0, run.stderr)

if __name__ == '__main__':
    unittest.main()
