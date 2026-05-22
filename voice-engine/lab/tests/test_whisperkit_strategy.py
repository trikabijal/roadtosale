"""Tests for the WhisperKitSTT subprocess wrapper.

Mirrors the approach used by ``test_apple_strategy.py``: the actual Swift
binary is replaced with a tiny shell-script fake that emits a known JSONL
transcript on stdout. That keeps the tests:

  - hermetic (no WhisperKit model download from HuggingFace, no Speech-
    framework prompt)
  - cross-platform for the wrapper-level assertions
  - independent of swift toolchain availability
"""

from __future__ import annotations

import os
import stat
from pathlib import Path
from typing import List

import pytest

from voice_lab.strategies.whisperkit import WhisperKitStrategy
from voice_lab.types import TranscriptionError


# ---------------------------------------------------------------------------
# Fake binary helpers
# ---------------------------------------------------------------------------


def _write_fake_binary(
    tmp_path: Path,
    *,
    stdout_lines: List[str],
    exit_code: int = 0,
    log_argv_path: Path | None = None,
) -> Path:
    """Write a tiny POSIX shell script that emulates the WhisperKitSTT CLI.

    - Optionally records its argv to ``log_argv_path`` so the tests can
      assert the wrapper passes the right flags.
    - Emits ``stdout_lines`` verbatim on stdout.
    - Exits with ``exit_code``.
    """
    script_path = tmp_path / "WhisperKitSTT-fake.sh"
    lines: list[str] = ["#!/usr/bin/env bash"]
    if log_argv_path is not None:
        lines.append(f'for arg in "$@"; do echo "$arg" >> "{log_argv_path}"; done')
    for line in stdout_lines:
        # Single-quote the JSON so the shell doesn't interpret it. Our
        # fixtures never contain single quotes.
        lines.append(f"echo '{line}'")
    lines.append(f"exit {exit_code}")
    script_path.write_text("\n".join(lines) + "\n", encoding="utf-8")
    st = script_path.stat()
    script_path.chmod(st.st_mode | stat.S_IEXEC | stat.S_IXGRP | stat.S_IXOTH)
    return script_path


def _make_audio_file(tmp_path: Path) -> Path:
    """Create a placeholder audio file. The fake binary ignores its contents
    but the wrapper checks existence."""
    p = tmp_path / "fake-audio.wav"
    p.write_bytes(b"RIFF\x00\x00\x00\x00WAVEfake-audio-payload")
    return p


# ---------------------------------------------------------------------------
# Happy path: JSONL parsing
# ---------------------------------------------------------------------------


def test_whisperkit_strategy_parses_jsonl(tmp_path):
    audio = _make_audio_file(tmp_path)
    fake = _write_fake_binary(
        tmp_path,
        stdout_lines=[
            '{"type":"partial","text":"hello","timestamp_ms":120,'
            '"latency_ms_from_audio_start":135,"confidence":0.87,'
            '"engine_metadata":{"engine":"whisperkit",'
            '"model":"openai_whisper-tiny.en","is_volatile":true,'
            '"window_id":0}}',
            '{"type":"final","text":"hello world","timestamp_ms":520,'
            '"latency_ms_from_audio_start":560,"confidence":0.94,'
            '"engine_metadata":{"engine":"whisperkit",'
            '"model":"openai_whisper-tiny.en","is_volatile":false,'
            '"segment_start_s":0.52,"segment_end_s":1.10,'
            '"avg_logprob":-0.062}}',
        ],
    )

    strat = WhisperKitStrategy(binary_path=fake)
    events = list(strat.transcribe(audio))

    assert len(events) == 2
    assert events[0].stability == "partial"
    assert events[0].text == "hello"
    assert events[0].timestamp_ms == 120
    assert events[0].latency_ms_from_audio_start == 135
    assert events[0].confidence == pytest.approx(0.87)
    assert events[0].engine_metadata["engine"] == "whisperkit"
    assert events[0].engine_metadata["is_volatile"] is True
    assert events[0].engine_metadata["window_id"] == 0

    assert events[1].stability == "final"
    assert events[1].text == "hello world"
    assert events[1].latency_ms_from_audio_start == 560
    assert events[1].confidence == pytest.approx(0.94)
    assert events[1].engine_metadata["avg_logprob"] == pytest.approx(-0.062)


# ---------------------------------------------------------------------------
# argv assertions
# ---------------------------------------------------------------------------


def test_whisperkit_passes_expected_argv(tmp_path):
    audio = _make_audio_file(tmp_path)
    argv_log = tmp_path / "argv.log"
    fake = _write_fake_binary(
        tmp_path,
        stdout_lines=[
            '{"type":"final","text":"x","timestamp_ms":0,'
            '"latency_ms_from_audio_start":0,"confidence":null,'
            '"engine_metadata":{}}'
        ],
        log_argv_path=argv_log,
    )

    strat = WhisperKitStrategy(binary_path=fake, model="openai_whisper-base.en")
    list(strat.transcribe(audio))

    args = argv_log.read_text(encoding="utf-8").splitlines()
    # Expect: --file <path> --model openai_whisper-base.en --partials true
    assert "--file" in args
    assert str(audio) in args
    assert "--model" in args
    assert "openai_whisper-base.en" in args
    assert "--partials" in args
    assert "true" in args
    # No --vocab when none provided.
    assert "--vocab" not in args


def test_whisperkit_default_model_used_when_env_unset(tmp_path, monkeypatch):
    monkeypatch.delenv("WHISPERKIT_MODEL", raising=False)
    audio = _make_audio_file(tmp_path)
    argv_log = tmp_path / "argv.log"
    fake = _write_fake_binary(
        tmp_path,
        stdout_lines=[
            '{"type":"final","text":"x","timestamp_ms":0,'
            '"latency_ms_from_audio_start":0,"confidence":null,'
            '"engine_metadata":{}}'
        ],
        log_argv_path=argv_log,
    )

    strat = WhisperKitStrategy(binary_path=fake)
    list(strat.transcribe(audio))
    args = argv_log.read_text(encoding="utf-8").splitlines()
    idx = args.index("--model")
    assert args[idx + 1] == "openai_whisper-tiny.en"


def test_whisperkit_env_var_overrides_default_model(tmp_path, monkeypatch):
    monkeypatch.setenv("WHISPERKIT_MODEL", "openai_whisper-large-v3-v20240930_626MB")
    audio = _make_audio_file(tmp_path)
    argv_log = tmp_path / "argv.log"
    fake = _write_fake_binary(
        tmp_path,
        stdout_lines=[
            '{"type":"final","text":"x","timestamp_ms":0,'
            '"latency_ms_from_audio_start":0,"confidence":null,'
            '"engine_metadata":{}}'
        ],
        log_argv_path=argv_log,
    )

    # No `model=` argument — should pick up the env var.
    strat = WhisperKitStrategy(binary_path=fake)
    list(strat.transcribe(audio))
    args = argv_log.read_text(encoding="utf-8").splitlines()
    idx = args.index("--model")
    assert args[idx + 1] == "openai_whisper-large-v3-v20240930_626MB"


def test_partials_false_serializes_to_false(tmp_path):
    audio = _make_audio_file(tmp_path)
    argv_log = tmp_path / "argv.log"
    fake = _write_fake_binary(
        tmp_path,
        stdout_lines=[
            '{"type":"final","text":"x","timestamp_ms":0,'
            '"latency_ms_from_audio_start":0,"confidence":null,'
            '"engine_metadata":{}}'
        ],
        log_argv_path=argv_log,
    )
    strat = WhisperKitStrategy(binary_path=fake, partials=False)
    list(strat.transcribe(audio))
    args = argv_log.read_text(encoding="utf-8").splitlines()
    idx = args.index("--partials")
    assert args[idx + 1] == "false"


def test_whisperkit_passes_vocab_flag(tmp_path):
    audio = _make_audio_file(tmp_path)
    vocab = tmp_path / "vocab.txt"
    vocab.write_text("Honda Sensing\nCR-V Hybrid AWD\n", encoding="utf-8")

    argv_log = tmp_path / "argv.log"
    fake = _write_fake_binary(
        tmp_path,
        stdout_lines=[
            '{"type":"final","text":"honda sensing","timestamp_ms":50,'
            '"latency_ms_from_audio_start":60,"confidence":0.91,'
            '"engine_metadata":{"engine":"whisperkit",'
            '"model":"openai_whisper-tiny.en"}}'
        ],
        log_argv_path=argv_log,
    )

    strat = WhisperKitStrategy(binary_path=fake, vocab_path=vocab)
    events = list(strat.transcribe(audio))

    assert len(events) == 1
    assert events[0].text == "honda sensing"

    args = argv_log.read_text(encoding="utf-8").splitlines()
    assert "--vocab" in args
    assert str(vocab) in args


def test_whisperkit_missing_vocab_raises(tmp_path):
    audio = _make_audio_file(tmp_path)
    fake = _write_fake_binary(
        tmp_path,
        stdout_lines=[
            '{"type":"final","text":"x","timestamp_ms":0,'
            '"latency_ms_from_audio_start":0,"confidence":null,'
            '"engine_metadata":{}}'
        ],
    )
    strat = WhisperKitStrategy(
        binary_path=fake, vocab_path=tmp_path / "does-not-exist.txt"
    )
    with pytest.raises(TranscriptionError, match="vocab file not found"):
        list(strat.transcribe(audio))


# ---------------------------------------------------------------------------
# Error paths
# ---------------------------------------------------------------------------


def test_missing_audio_file_raises_clear_error(tmp_path):
    fake = _write_fake_binary(tmp_path, stdout_lines=[])
    strat = WhisperKitStrategy(binary_path=fake)
    with pytest.raises(TranscriptionError, match="audio file not found"):
        list(strat.transcribe(tmp_path / "nope.wav"))


def test_missing_binary_raises_with_build_hint(tmp_path):
    audio = _make_audio_file(tmp_path)
    strat = WhisperKitStrategy(binary_path=tmp_path / "does-not-exist")
    with pytest.raises(TranscriptionError) as excinfo:
        list(strat.transcribe(audio))
    msg = str(excinfo.value)
    assert "WhisperKitSTT binary not found" in msg
    # Hint must point to a build path or toolchain.
    assert "build" in msg.lower()


def test_nonzero_exit_surfaces_stderr(tmp_path):
    audio = _make_audio_file(tmp_path)
    script_path = tmp_path / "WhisperKitSTT-fail.sh"
    script_path.write_text(
        "#!/usr/bin/env bash\n"
        "echo '{\"type\":\"partial\",\"text\":\"hi\",\"timestamp_ms\":0,"
        '"latency_ms_from_audio_start":1,"confidence":null,'
        "\"engine_metadata\":{}}'\n"
        'echo "WhisperKitSTT error: model download failed" >&2\n'
        "exit 50\n",
        encoding="utf-8",
    )
    st = script_path.stat()
    script_path.chmod(st.st_mode | stat.S_IEXEC | stat.S_IXGRP | stat.S_IXOTH)

    strat = WhisperKitStrategy(binary_path=script_path)
    with pytest.raises(TranscriptionError) as excinfo:
        list(strat.transcribe(audio))
    msg = str(excinfo.value)
    assert "exited with code 50" in msg
    assert "model download failed" in msg


def test_malformed_json_raises_transcription_error(tmp_path):
    audio = _make_audio_file(tmp_path)
    fake = _write_fake_binary(
        tmp_path,
        stdout_lines=[
            "this is not json",
        ],
    )
    strat = WhisperKitStrategy(binary_path=fake)
    with pytest.raises(TranscriptionError, match="failed to parse"):
        list(strat.transcribe(audio))


def test_missing_required_field_raises_transcription_error(tmp_path):
    audio = _make_audio_file(tmp_path)
    fake = _write_fake_binary(
        tmp_path,
        stdout_lines=[
            # Missing latency_ms_from_audio_start
            '{"type":"final","text":"x","timestamp_ms":0,'
            '"confidence":null,"engine_metadata":{}}'
        ],
    )
    strat = WhisperKitStrategy(binary_path=fake)
    with pytest.raises(TranscriptionError, match="malformed event"):
        list(strat.transcribe(audio))


# ---------------------------------------------------------------------------
# Registry integration
# ---------------------------------------------------------------------------


def test_registry_registers_whisperkit_strategy():
    from voice_lab import VoiceEngineLab

    engine = VoiceEngineLab.load()
    names = set(engine.list_strategies())
    assert "whisperkit" in names


def test_registry_returns_real_whisperkit_implementation():
    """The strategy must be the real subprocess-wrapper class."""
    from voice_lab import VoiceEngineLab

    engine = VoiceEngineLab.load()
    wk = engine.get_strategy("whisperkit")
    assert isinstance(wk, WhisperKitStrategy)


# ---------------------------------------------------------------------------
# Real binary smoke check (skipped unless built; --help only)
# ---------------------------------------------------------------------------


def _real_binary_path() -> Path:
    """Same resolution rule as the strategy."""
    override = os.environ.get("WHISPERKIT_BIN")
    if override:
        return Path(override).expanduser()
    return (
        Path(__file__).resolve().parents[2]
        / "native"
        / "apple"
        / "WhisperKitSTT"
        / ".build"
        / "release"
        / "WhisperKitSTT"
    )


@pytest.mark.skipif(
    not _real_binary_path().exists(),
    reason=(
        "WhisperKitSTT release binary not built. Run "
        "voice-engine/native/apple/whisperkit_build.sh first."
    ),
)
def test_real_binary_responds_to_help():
    """If the Swift CLI has been built, --help should exit 0 and print usage.
    End-to-end transcription is not exercised here because it requires a
    one-time model download from HuggingFace."""
    import subprocess

    result = subprocess.run(
        [str(_real_binary_path()), "--help"],
        capture_output=True,
        text=True,
    )
    assert result.returncode == 0
    assert "WhisperKitSTT" in (result.stderr + result.stdout)
