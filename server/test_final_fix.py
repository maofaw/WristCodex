import unittest
from desktop_ipc import Projection
from test_shared_assistant import state

class ModelSnapshotTests(unittest.TestCase):
    def test_live_native_model_precedence(self):
        raw=state(status="idle")
        for native,collab,saved,want in [("native", "collab", "saved", "collab"),("native",None,"saved","native"),(None,None,"saved","saved"),(None,None,None,None)]:
            raw.update(latestModel=native,latestCollaborationMode={"settings":{"model":collab}},latestThreadSettings={"model":saved})
            projection=Projection("owner");projection.accept(dict(type="snapshot",revision=1,conversationState=raw))
            self.assertEqual(projection.snapshot().get("model"),want)

    def test_shared_snapshot_keeps_projected_native_model(self):
        import tempfile
        from pathlib import Path
        from codex_session import Assistant
        from shared_assistant import SharedAssistant
        from test_shared_assistant import FakeDesktop
        with tempfile.TemporaryDirectory() as directory:
            desktop=FakeDesktop()
            native=state(status="idle");native["latestModel"]="real-native-model";native["latestCollaborationMode"]={}
            projection=Projection("owner");projection.accept(dict(type="snapshot",revision=1,conversationState=native))
            desktop.sessions["pc1"]=projection
            assistant=SharedAssistant(Assistant(Path(directory)/"state.json"),desktop=desktop)
            self.assertEqual(assistant.snapshot(True,"pc1")["model"],"real-native-model")
            self.assertEqual(desktop.calls,[],"snapshot needs no model/settings execution")
