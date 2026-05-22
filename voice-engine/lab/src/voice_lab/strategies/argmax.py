"""Argmax Pro SDK 2 strategy via Argmax Local Server WebSocket.

Argmax Pro SDK 2 does not yet ship official Python bindings (per
`dev/docs/ROAD_TO_SALE_AUDIO_RESEARCH_ARGMAX.md`). The recommended Python
path is the Argmax Local Server, whose WebSocket is Deepgram Streaming-STT
compatible. This strategy:

1. Reads a 16 kHz mono PCM-16 WAV file.
2. Opens a WebSocket to `ws://localhost:50060/v1/listen` (overridable via
   the ARGMAX_LOCAL_SERVER_HOST env var).
3. Authenticates with the `ax_***` API key from ARGMAX_API_KEY.
4. Streams audio frames as binary messages.
5. Parses Deepgram-style JSON responses and yields TranscriptEvent objects.
   - is_final=true  -> stability='final'   (maps to Argmax 'Confirmed')
   - is_final=false -> stability='partial' (maps to Argmax 'Hypothesis')

Setup (user-side):
- Pay the $14 self-serve trial at app.argmaxinc.com (30 device licenses).
- Email customer@argmaxinc.com for the macOS Local Server binary.
- Run the binary locally; it listens on ws://localhost:50060 by default.
- `export ARGMAX_API_KEY=ax_<your_key>`.
- `pip install -e '.[argmax]'` to install the websockets dependency.

Audio format requirement: 16 kHz mono PCM-16 WAV. Convert other formats
before passing in (e.g. `ffmpeg -i input.mp3 -ar 16000 -ac 1 -sample_fmt s16 out.wav`).
"""

from __future__ import annotations

import asyncio
import json
import os
import time
import wave
from pathlib import Path
from typing import Iterable, Iterator

from voice_lab.strategies.base import TranscriptionStrategy
from voice_lab.types import AudioFileError, TranscriptEvent, TranscriptionError

_DEFAULT_HOST = "ws://localhost:50060"
_DEFAULT_PATH = "/v1/listen"
_FRAME_DURATION_MS = 100  # 100ms frames -> 1600 samples at 16kHz mono
_TARGET_SAMPLE_RATE = 16_000
_TARGET_CHANNELS = 1
_TARGET_SAMPWIDTH = 2  # 16-bit


class ArgmaxStrategy(TranscriptionStrategy):
    name = "argmax"

    def __init__(
        self,
        host: str | None = None,
        api_key: str | None = None,
        path: str = _DEFAULT_PATH,
        connect_timeout_s: float = 5.0,
    ) -> None:
        self.host = host or os.environ.get("ARGMAX_LOCAL_SERVER_HOST", _DEFAULT_HOST)
        self.api_key = api_key or os.environ.get("ARGMAX_API_KEY", "")
        self.path = path
        self.connect_timeout_s = connect_timeout_s

    def transcribe(self, audio_path: Path) -> Iterable[TranscriptEvent]:
        if not self.api_key:
            raise TranscriptionError(
                "ARGMAX_API_KEY env var not set. "
                "Buy the $14 self-serve trial at app.argmaxinc.com (30 device licenses), "
                "email customer@argmaxinc.com for the macOS Local Server binary, "
                "then `export ARGMAX_API_KEY=ax_<key>`."
            )
        try:
            import websockets  # noqa: F401
        except ImportError as exc:
            raise TranscriptionError(
                "The 'websockets' package is not installed. "
                "Run `pip install -e '.[argmax]'` from voice-engine/lab."
            ) from exc

        frames = list(_read_wav_frames(audio_path))
        events = asyncio.run(self._run_session(frames))
        yield from events

    async def _run_session(self, frames: list[bytes]) -> list[TranscriptEvent]:
        import websockets
        from websockets.exceptions import WebSocketException

        url = (
            f"{self.host}{self.path}"
            f"?model=argmax&encoding=linear16&sample_rate={_TARGET_SAMPLE_RATE}"
            f"&channels={_TARGET_CHANNELS}&interim_results=true"
        )
        headers = [("Authorization", f"Token {self.api_key}")]

        try:
            connect = websockets.connect(
                url,
                additional_headers=headers,
                open_timeout=self.connect_timeout_s,
            )
        except TypeError:
            connect = websockets.connect(
                url,
                extra_headers=headers,
                open_timeout=self.connect_timeout_s,
            )

        events: list[TranscriptEvent] = []
        session_start = time.monotonic()

        try:
            async with connect as ws:
                send_task = asyncio.create_task(_send_frames(ws, frames))
                async for raw in ws:
                    parsed = _parse_message(raw, session_start)
                    if parsed is not None:
                        events.append(parsed)
                    if isinstance(raw, (str, bytes)) and _is_session_end(raw):
                        break
                await send_task
        except WebSocketException as exc:
            raise TranscriptionError(
                f"Argmax Local Server WebSocket error at {self.host}{self.path}: {exc}. "
                "Confirm the Local Server is running and ARGMAX_API_KEY is valid."
            ) from exc
        except OSError as exc:
            raise TranscriptionError(
                f"Could not connect to Argmax Local Server at {self.host}{self.path}. "
                "Start the macOS Local Server binary locally (see "
                "dev/docs/ROAD_TO_SALE_AUDIO_RESEARCH_ARGMAX.md)."
            ) from exc

        return events


def _read_wav_frames(audio_path: Path) -> Iterator[bytes]:
    if not audio_path.exists():
        raise AudioFileError(f"Audio file not found: {audio_path}")
    try:
        with wave.open(str(audio_path), "rb") as wf:
            sr = wf.getframerate()
            ch = wf.getnchannels()
            sw = wf.getsampwidth()
            if sr != _TARGET_SAMPLE_RATE or ch != _TARGET_CHANNELS or sw != _TARGET_SAMPWIDTH:
                raise AudioFileError(
                    f"Argmax strategy requires 16 kHz mono PCM-16 WAV; "
                    f"got sample_rate={sr} channels={ch} sampwidth={sw}. "
                    f"Convert with: ffmpeg -i {audio_path} -ar 16000 -ac 1 "
                    f"-sample_fmt s16 converted.wav"
                )
            samples_per_frame = (sr * _FRAME_DURATION_MS) // 1000
            while True:
                data = wf.readframes(samples_per_frame)
                if not data:
                    break
                yield data
    except wave.Error as exc:
        raise AudioFileError(f"Could not read WAV file {audio_path}: {exc}") from exc


async def _send_frames(ws, frames: list[bytes]) -> None:
    for frame in frames:
        await ws.send(frame)
        await asyncio.sleep(_FRAME_DURATION_MS / 1000.0)
    try:
        await ws.send(json.dumps({"type": "CloseStream"}))
    except Exception:
        pass


def _parse_message(raw, session_start: float) -> TranscriptEvent | None:
    if isinstance(raw, bytes):
        return None
    try:
        msg = json.loads(raw)
    except (json.JSONDecodeError, TypeError):
        return None
    if not isinstance(msg, dict):
        return None
    if msg.get("type") not in (None, "Results"):
        return None
    channel = msg.get("channel") or {}
    alternatives = channel.get("alternatives") or []
    if not alternatives:
        return None
    primary = alternatives[0]
    text = primary.get("transcript") or ""
    if not text.strip():
        return None
    is_final = bool(msg.get("is_final"))
    start_seconds = float(msg.get("start") or 0.0)
    timestamp_ms = int(start_seconds * 1000)
    latency_ms = int((time.monotonic() - session_start) * 1000)
    confidence = primary.get("confidence")
    if confidence is not None:
        try:
            confidence = float(confidence)
        except (TypeError, ValueError):
            confidence = None
    engine_metadata = {
        "engine": "argmax_local_server",
        "deepgram_compatible": True,
        "is_final": is_final,
        "speech_final": bool(msg.get("speech_final")),
        "duration_s": msg.get("duration"),
    }
    return TranscriptEvent(
        text=text,
        stability="final" if is_final else "partial",
        timestamp_ms=timestamp_ms,
        latency_ms_from_audio_start=latency_ms,
        confidence=confidence,
        engine_metadata=engine_metadata,
    )


def _is_session_end(raw) -> bool:
    if isinstance(raw, bytes):
        return False
    try:
        msg = json.loads(raw)
    except (json.JSONDecodeError, TypeError):
        return False
    return isinstance(msg, dict) and msg.get("type") == "Metadata"
