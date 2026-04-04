import io
import wave
from pathlib import Path

from openai import OpenAI

from .config import get_settings


def _parse_wav_bytes(data: bytes) -> tuple[bytes, int, int, int]:
    """Return pcm_bytes, channels, sample_width, frame_rate."""
    bio = io.BytesIO(data)
    with wave.open(bio, "rb") as wf:
        channels = wf.getnchannels()
        sample_width = wf.getsampwidth()
        rate = wf.getframerate()
        pcm = wf.readframes(wf.getnframes())
    return pcm, channels, sample_width, rate


def transcribe_wav(wav_bytes: bytes) -> str:
    settings = get_settings()
    if not settings.openai_api_key:
        raise RuntimeError("OPENAI_API_KEY not configured")
    _pcm, channels, _sw, rate = _parse_wav_bytes(wav_bytes)
    if channels != 1:
        raise ValueError("Expected mono WAV")
    if rate != 16000:
        # Whisper accepts various rates; OpenAI API accepts multiple formats
        pass
    client = OpenAI(api_key=settings.openai_api_key)
    bio = io.BytesIO(wav_bytes)
    bio.name = "command.wav"
    tr = client.audio.transcriptions.create(model="whisper-1", file=bio, response_format="text")
    if isinstance(tr, str):
        return tr.strip()
    return str(tr).strip()


def transcribe_wav_file(path: Path) -> str:
    return transcribe_wav(path.read_bytes())
