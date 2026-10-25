"""Host-JVM contract for independently configured bridge transport."""
from pathlib import Path
import os, subprocess, tempfile, unittest
ROOT=Path(__file__).resolve().parents[1]
class BridgeNetworkTests(unittest.TestCase):
    def test_defaults_overrides_and_long_poll_guard(self):
        home=Path(os.environ.get('JAVA_HOME',r'C:\Program Files\Android\Android Studio\jbr'))
        files=[ROOT/'tests/java/com/n/watchremote/BridgeNetworkConfigTest.java']
        production=ROOT/'app/src/main/java/com/n/watchremote/BridgeNetworkConfig.java'
        if production.exists():files.append(production)
        with tempfile.TemporaryDirectory() as temp:
            built=subprocess.run([str(home/'bin/javac.exe'),'-encoding','UTF-8','-d',temp,*map(str,files)],capture_output=True,text=True,encoding='utf-8')
            self.assertEqual(built.returncode,0,built.stderr)
            run=subprocess.run([str(home/'bin/java.exe'),'-cp',temp,'com.n.watchremote.BridgeNetworkConfigTest'],capture_output=True,text=True,encoding='utf-8')
            self.assertEqual(run.returncode,0,run.stderr)
