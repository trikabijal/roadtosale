"""Tests for the Argmax strategy — the message parser and WAV reader.

End-to-end WebSocket integration is gated on a running Argmax Local Server
and a valid ARGMAX_API_KEY, so we test the deterministic pieces in
isolation: WAV frame extraction, Deepgram-style response parsing, and the
mapping to TranscriptEvent.
"""

from __future__ import annotations

import json
import struct
import wave
from pathlib import Path

import pytest

from voice_lab.strategies.argmax import (
    ArgmaxStrategy,
    _parse_message,
    _read_wav_frames,
)
from voice_lab.types import AudioFileError, TranscriptionError


def _write_silent_wav(path: Path, sample_rate: int, channels: int, sampwidth: int, ms: int) -> None:
    n_frames = (sample_rate * ms) // 1000
    silence = struct.pack("<h", 0) * (n_frames * channels)
    with wave.open(str(path), "wb") as wf:
        wf.setnchannels(channels)
        wf.setsampwidth(sampwidth)
        wf.setframerate(sample_rate)
        wf.writeframes(silence)


def test_read_wav_frames_emits_chunks(tmp_path: Path) -> None:
    wav = tmp_path / "test.wav"
    _write_silent_wav(wav, sample_rate=16_000, channels=1, sampwidth=2, ms=500)
    frames = list(_read_wav_frames(wav))
    assert len(frames) == 5
    assert all(isinstance(f, bytes) for f in frames)
    assert all(len(f) == 1600 * 2 for f in frames)


def test_read_wav_rejects_wrong_sample_rate(tmp_path: Path) -> None:
    wav = tmp_path / "wrong.wav"
    _write_silent_wav(wav, sample_rate=44_100, channels=1, sampwidth=2, ms=100)
    with pytest.raises(AudioFileError, match="16 kHz mono PCM-16"):
        list(_read_wav_frames(wav))


def test_read_wav_rejects_stereo(tmp_path: Path) -> None:
    wav = tmp_path / "stereo.wav"
    _write_silent_wav(wav, sample_rate=16_000, channels=2, sampwidth=2, ms=100)
    with pytest.raises(AudioFileError, match="channels=2"):
        list(_read_wav_frames(wav))


def test_read_wav_missing_file(tmp_path: Path) -> None:
    with pytest.raises(AudioFileError, match="not found"):
        list(_read_wav_frames(tmp_path / "missing.wav"))


def test_parse_partial_message() -> None:
    msg = json.dumps(
        {
            "type": "Results",
            "is_final": False,
            "start": 1.2,
            "duration": 0.5,
            "channel": {
                "alternatives": [
                    {"transcript": "hello world", "confidence": 0.92}
                ]
            },
        }
    )
    event = _parse_message(msg, session_start=0.0)
    assert event is not None
    assert event.text == "hello world"
    assert event.stability == "partial"
    assert event.timestamp_ms == 1200
    assert event.confidence == pytest.approx(0.92)
    assert event.engine_metadata["engine"] == "argmax_local_server"
    assert event.engine_metadata["is_final"] is False


def test_parse_final_message() -> None:
    msg = json.dumps(
        {
            "type": "Results",
            "is_final": True,
            "speech_final": True,
            "start": 3.4,
            "channel": {
                "alternatives": [{"transcript": "the CR-V Hybrid AWD Sport Touring", "confidence": 0.98}]
            },
        }
    )
    event = _parse_message(msg, session_start=0.0)
    assert event is not None
    assert event.stability == "final"
    assert event.text == "the CR-V Hybrid AWD Sport Touring"
    assert event.engine_metadata["is_final"] is True
    assert event.engine_metadata["speech_final"] is True


def test_parse_ignores_empty_transcripts() -> None:
    msg = json.dumps(
        {
            "type": "Results",
            "is_final": False,
            "channel": {"alternatives": [{"transcript": "", "confidence": 0.0}]},
        }
    )
    assert _parse_message(msg, session_start=0.0) is None


def test_parse_ignores_binary_messages() -> None:
    assert _parse_message(b"\x00\x01\x02", session_start=0.0) is None


def test_parse_ignores_malformed_json() -> None:
    assert _parse_message("not-json", session_start=0.0) is None


def test_parse_ignores_non_results_messages() -> None:
    msg = json.dumps({"type": "Metadata", "request_id": "abc"})
    assert _parse_message(msg, session_start=0.0) is None


def test_transcribe_without_api_key_raises_clearly(monkeypatch, tmp_path: Path) -> None:
    monkeypatch.delenv("ARGMAX_API_KEY", raising=False)
    monkeypatch.delenv("ARGMAX_LOCAL_SERVER_HOST", raising=False)
    wav = tmp_path / "test.wav"
    _write_silent_wav(wav, sample_rate=16_000, channels=1, sampwidth=2, ms=100)
    strategy = ArgmaxStrategy()
    with pytest.raises(TranscriptionError, match="ARGMAX_API_KEY"):
        list(strategy.transcribe(wav))
