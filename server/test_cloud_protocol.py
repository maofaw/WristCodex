"""Host JVM checks; no microphone, cloud credentials or billable requests."""
from pathlib import Path
import os,shutil,subprocess,tempfile,unittest
ROOT=Path(__file__).resolve().parents[1]
class CloudProtocolTests(unittest.TestCase):
    def test_binary_protocol_and_partial_draft_isolation(self):
        home=Path(os.environ.get('JAVA_HOME',r'C:\Program Files\Android\Android Studio\jbr'))
        javac=shutil.which('javac') or str(home/'bin/javac.exe')
        java=shutil.which('java') or str(home/'bin/java.exe')
        if not Path(javac).exists():self.skipTest('JDK required')
        files=[ROOT/'app/src/main/java/com/n/watchremote'/name for name in ('CloudProtocol.java','VoiceDraft.java')]
        files.append(ROOT/'tests/java/com/n/watchremote/CloudProtocolTest.java')
        with tempfile.TemporaryDirectory() as d:
            p=subprocess.run([javac,'-encoding','UTF-8','-d',d,*map(str,files)],capture_output=True,text=True,encoding='utf-8',errors='replace')
            self.assertEqual(p.returncode,0,p.stderr)
            p=subprocess.run([java,'-cp',d,'com.n.watchremote.CloudProtocolTest'],capture_output=True,text=True,encoding='utf-8',errors='replace')
            self.assertEqual(p.returncode,0,p.stderr)
