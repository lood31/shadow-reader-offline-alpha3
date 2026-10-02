import io
import wave
import numpy as np
from .schemas import AssessmentError

RATE = 16000
MAX_BYTES = 1_048_576

def decode_wav(data: bytes) -> np.ndarray:
    if len(data) > MAX_BYTES:
        raise AssessmentError('AUDIO_TOO_LARGE', 'WAV exceeds 1 MiB', 413)
    try:
        with wave.open(io.BytesIO(data)) as wav:
            if (wav.getnchannels(), wav.getsampwidth(), wav.getframerate(), wav.getcomptype()) != (1, 2, RATE, 'NONE'):
                raise AssessmentError('INVALID_AUDIO_FORMAT', 'Use mono 16 kHz PCM16 WAV')
            frames = wav.getnframes()
            if not RATE // 2 <= frames <= RATE * 30:
                raise AssessmentError('INVALID_AUDIO_DURATION', 'Audio must be 0.5–30 seconds')
            raw = wav.readframes(frames)
            if len(raw) != frames * 2:
                raise AssessmentError('TRUNCATED_AUDIO', 'Incomplete WAV data')
    except (wave.Error, EOFError, ValueError) as error:
        raise AssessmentError('INVALID_AUDIO_FORMAT', 'Invalid WAV container') from error
    return np.frombuffer(raw, dtype='<i2').astype(np.float32) / 32768.0

def audio_quality(samples: np.ndarray) -> tuple[bool, str | None]:
    if float(np.sqrt(np.mean(samples ** 2))) < .003:
        return False, 'NO_SPEECH'
    if float(np.mean(np.abs(samples) >= .999)) > .02:
        return False, 'CLIPPED_AUDIO'
    return True, None
