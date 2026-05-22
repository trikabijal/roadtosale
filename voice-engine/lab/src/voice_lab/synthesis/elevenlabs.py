"""ElevenLabs TTS synthesis provider.

Calls the ElevenLabs Text-to-Speech API with `output_format=pcm_16000`
and wraps the returned raw PCM as a 16 kHz mono PCM-16 WAV file — the
exact input format the Argmax / AppleSTT / WhisperKit strategies all
expect.

Idempotent: if the requested output_path already exists, returns it
without re-fetching. Force regeneration by deleting the file.

Required env vars (either name accepted):
  ELEVENLABS_KEY  or  ELEVENLABS_API_KEY

Optional:
  ELEVENLABS_MODEL  (default: eleven_multilingual_v2)
"""

from __future__ import annotations

import json
import os
import urllib.error
import urllib.request
import wave
from pathlib import Path

from voice_lab.synthesis.base import SynthesisProvider, SynthesisRequest

_TTS_ENDPOINT = "https://api.elevenlabs.io/v1/text-to-speech"
_DEFAULT_MODEL = "eleven_multilingual_v2"
_OUTPUT_FORMAT = "pcm_16000"  # 16 kHz mono signed 16-bit PCM, raw bytes
_SAMPLE_RATE = 16_000


class ElevenLabsSynthesisProvider(SynthesisProvider):
    name = "elevenlabs"

    def __init__(
        self,
        api_key: str | None = None,
        model_id: str | None = None,
        timeout_s: float = 60.0,
    ) -> None:
        self.api_key = (
            api_key
            or os.environ.get("ELEVENLABS_KEY")
            or os.environ.get("ELEVENLABS_API_KEY")
        )
        self.model_id = model_id or os.environ.get("ELEVENLABS_MODEL", _DEFAULT_MODEL)
        self.timeout_s = timeout_s

    def synthesize(self, request: SynthesisRequest) -> Path:
        if request.output_path.exists() and request.output_path.stat().st_size > 0:
            return request.output_path

        if not self.api_key:
            raise SynthesisError(
                "ElevenLabs API key not found. Set ELEVENLABS_KEY (or "
                "ELEVENLABS_API_KEY) in your environment or .env file."
            )

        url = (
            f"{_TTS_ENDPOINT}/{request.voice_id}"
            f"?output_format={_OUTPUT_FORMAT}"
        )
        body = json.dumps(
            {
                "text": request.text,
                "model_id": self.model_id,
                "voice_settings": {
                    "stability": 0.5,
                    "similarity_boost": 0.75,
                    "style": 0.0,
                    "use_speaker_boost": True,
                },
            }
        ).encode("utf-8")
        headers = {
            "xi-api-key": self.api_key,
            "Content-Type": "application/json",
            "Accept": "audio/pcm",
        }

        req = urllib.request.Request(url, data=body, headers=headers, method="POST")
        try:
            with urllib.request.urlopen(req, timeout=self.timeout_s) as resp:
                pcm_bytes = resp.read()
        except urllib.error.HTTPError as exc:
            detail = exc.read().decode("utf-8", errors="replace")[:500]
            raise SynthesisError(
                f"ElevenLabs TTS failed for voice_id={request.voice_id}: "
                f"HTTP {exc.code} — {detail}"
            ) from exc
        except urllib.error.URLError as exc:
            raise SynthesisError(
                f"ElevenLabs TTS network failure: {exc.reason}"
            ) from exc

        if not pcm_bytes:
            raise SynthesisError(
                f"ElevenLabs returned empty audio for voice_id={request.voice_id}"
            )

        request.output_path.parent.mkdir(parents=True, exist_ok=True)
        _write_wav(request.output_path, pcm_bytes, sample_rate=_SAMPLE_RATE)
        return request.output_path


class SynthesisError(Exception):
    pass


def _write_wav(path: Path, pcm_bytes: bytes, sample_rate: int) -> None:
    with wave.open(str(path), "wb") as wf:
        wf.setnchannels(1)
        wf.setsampwidth(2)
        wf.setframerate(sample_rate)
        wf.writeframes(pcm_bytes)
