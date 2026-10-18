"""Local CPU transcription. Audio is processed in memory and not retained."""
from pathlib import Path
import io
import threading
import wave

LOCK=threading.Lock()
MODEL=None

def transcribe(audio):
    global MODEL
    if not audio.startswith(b'RIFF') or len(audio)<1000:raise ValueError('录音无效或太短')
    if not LOCK.acquire(blocking=False):raise RuntimeError('语音识别忙，请稍后重试')
    try:
        from faster_whisper import WhisperModel
        if MODEL is None:
            MODEL=WhisperModel('base',device='cpu',compute_type='int8',cpu_threads=4,
                download_root=str(Path(__file__).with_name('models')),local_files_only=True)
        import numpy as np
        with wave.open(io.BytesIO(audio),'rb') as wav:
            rate=wav.getframerate();channels=wav.getnchannels()
            if wav.getsampwidth()!=2 or channels not in (1,2) or rate<=0 or wav.getnframes()/rate>31:
                raise ValueError('需要 30 秒内的 16 位 PCM WAV')
            samples=np.frombuffer(wav.readframes(wav.getnframes()),dtype='<i2').astype(np.float32)/32768.0
            if channels==2:samples=samples.reshape(-1,2).mean(axis=1)
            if rate!=16000:
                samples=np.interp(np.arange(round(len(samples)*16000/rate))*rate/16000,
                                  np.arange(len(samples)),samples).astype(np.float32)
        segments,_=MODEL.transcribe(samples,language='zh',beam_size=1,
                                    condition_on_previous_text=False,vad_filter=False)
        text=''.join(s.text for s in segments).strip()
        if not text:raise ValueError('没有识别到文字，请重试')
        return text[:4000]
    except ImportError:
        raise RuntimeError('电脑未安装语音组件，请运行语音安装步骤')
    except (FileNotFoundError,OSError) as exc:
        raise RuntimeError('电脑缺少语音模型，请先下载 base 模型') from exc
    finally:LOCK.release()
