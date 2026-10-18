"""Run once after installing requirements-voice.txt; no API key required."""
from pathlib import Path
from faster_whisper import WhisperModel
WhisperModel('base',device='cpu',compute_type='int8',download_root=str(Path(__file__).with_name('models')))
print('Local voice model is ready')
