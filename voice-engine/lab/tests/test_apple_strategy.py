"""Tests for the AppleSTT subprocess wrappers.

These tests exercise the Python side of the integration only — the actual
Swift binary is replaced with a tiny shell-script fake that emits a known
JSONL transcript on stdout. That keeps the tests:

  - hermetic (no Speech-framework auth prompt, no microphone, no model
    download)
  - cross-platform for the wrapper-level assertions
  - independent of swift toolchain availability

A separate test (``test_real_binary_responds_to_help``) verifies that the
real, built AppleSTT binary is wired up if present, skipping otherwise.
"""

from __future__ import annotations

import os
import stat
from pathlib import Path
from typing import List

import pytest

from voice_lab.strategies.apple_speech_transcriber import (
    AppleSpeechTranscriberStrategy,
)
from voice_lab.strategies.apple_sfspeechrecognizer import (
    AppleSFSpeechRecognizerStrategy,
)
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
    """Write a tiny POSIX shell script that emulates the AppleSTT CLI.

    - Optionally records its argv to ``log_argv_path`` so the tests can
      assert the wrapper passes the right flags.
    - Emits ``stdout_lines`` verbatim on stdout.
    - Exits with ``exit_code``.
    """
    script_path = tmp_path / "AppleSTT-fake.sh"
    lines: list[str] = ["#!/usr/bin/env bash"]
    if log_argv_path is not None:
        # Append each arg on its own line to the log file.
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
# SpeechTranscriber wrapper
# ---------------------------------------------------------------------------


def test_speech_transcriber_strategy_parses_jsonl(tmp_path):
    audio = _make_audio_file(tmp_path)
    fake = _write_fake_binary(
        tmp_path,
        stdout_lines=[
            '{"type":"partial","text":"hello","timestamp_ms":120,'
            '"latency_ms_from_audio_start":135,"confidence":null,'
            '"engine_metadata":{"engine":"speech_transcriber","is_volatile":true}}',
            '{"type":"final","text":"hello world","timestamp_ms":520,'
            '"latency_ms_from_audio_start":560,"confidence":null,'
            '"engine_metadata":{"engine":"speech_transcriber","is_volatile":false}}',
        ],
    )

    strat = AppleSpeechTranscriberStrategy(binary_path=fake)
    events = list(strat.transcribe(audio))

    assert len(events) == 2
    assert events[0].stability == "partial"
    assert events[0].text == "hello"
    assert events[0].timestamp_ms == 120
    assert events[0].latency_ms_from_audio_start == 135
    assert events[0].confidence is None
    assert events[0].engine_metadata["engine"] == "speech_transcriber"
    assert events[0].engine_metadata["is_volatile"] is True

    assert events[1].stability == "final"
    assert events[1].text == "hello world"
    assert events[1].latency_ms_from_audio_start == 560


def test_speech_transcriber_passes_expected_argv(tmp_path):
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

    strat = AppleSpeechTranscriberStrategy(
        binary_path=fake, locale="en-US", partials=True
    )
    list(strat.transcribe(audio))

    args = argv_log.read_text(encoding="utf-8").splitlines()
    # Expect: --file <path> --mode speech_transcriber --locale en-US --partials true
    assert "--file" in args
    assert str(audio) in args
    assert "--mode" in args
    assert "speech_transcriber" in args
    assert "--locale" in args
    assert "en-US" in args
    assert "--partials" in args
    assert "true" in args
    # No --vocab on this strategy.
    assert "--vocab" not in args


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
    strat = AppleSpeechTranscriberStrategy(binary_path=fake, partials=False)
    list(strat.transcribe(audio))
    args = argv_log.read_text(encoding="utf-8").splitlines()
    # Find --partials and check the next token is "false"
    idx = args.index("--partials")
    assert args[idx + 1] == "false"


# ---------------------------------------------------------------------------
# SFSpeechRecognizer wrapper
# ---------------------------------------------------------------------------


def test_sfspeechrecognizer_passes_vocab_flag(tmp_path):
    audio = _make_audio_file(tmp_path)
    vocab = tmp_path / "vocab.txt"
    vocab.write_text("Honda Sensing\nCR-V Hybrid AWD\n", encoding="utf-8")

    argv_log = tmp_path / "argv.log"
    fake = _write_fake_binary(
        tmp_path,
        stdout_lines=[
            '{"type":"partial","text":"honda","timestamp_ms":50,'
            '"latency_ms_from_audio_start":60,"confidence":null,'
            '"engine_metadata":{"engine":"sfspeech_recognizer",'
            '"is_volatile":true,"vocab_terms":2}}',
            '{"type":"final","text":"honda sensing","timestamp_ms":700,'
            '"latency_ms_from_audio_start":750,"confidence":0.91,'
            '"engine_metadata":{"engine":"sfspeech_recognizer",'
            '"is_volatile":false,"vocab_terms":2}}',
        ],
        log_argv_path=argv_log,
    )

    strat = AppleSFSpeechRecognizerStrategy(
        binary_path=fake, vocab_path=vocab, locale="en-US"
    )
    events = list(strat.transcribe(audio))

    assert len(events) == 2
    assert events[0].stability == "partial"
    assert events[1].stability == "final"
    assert events[1].confidence == pytest.approx(0.91)
    assert events[1].engine_metadata["vocab_terms"] == 2

    args = argv_log.read_text(encoding="utf-8").splitlines()
    assert "--mode" in args
    assert "sfspeech_recognizer" in args
    assert "--vocab" in args
    assert str(vocab) in args


def test_sfspeechrecognizer_missing_vocab_raises(tmp_path):
    audio = _make_audio_file(tmp_path)
    fake = _write_fake_binary(
        tmp_path,
        stdout_lines=[
            '{"type":"final","text":"x","timestamp_ms":0,'
            '"latency_ms_from_audio_start":0,"confidence":null,'
            '"engine_metadata":{}}'
        ],
    )
    strat = AppleSFSpeechRecognizerStrategy(
        binary_path=fake, vocab_path=tmp_path / "does-not-exist.txt"
    )
    with pytest.raises(TranscriptionError, match="vocab file not found"):
        list(strat.transcribe(audio))


# ---------------------------------------------------------------------------
# Error paths
# ---------------------------------------------------------------------------


def test_missing_audio_file_raises_clear_error(tmp_path):
    fake = _write_fake_binary(tmp_path, stdout_lines=[])
    strat = AppleSpeechTranscriberStrategy(binary_path=fake)
    with pytest.raises(TranscriptionError, match="audio file not found"):
        list(strat.transcribe(tmp_path / "nope.wav"))


def test_missing_binary_raises_with_build_hint(tmp_path):
    audio = _make_audio_file(tmp_path)
    strat = AppleSpeechTranscriberStrategy(
        binary_path=tmp_path / "does-not-exist"
    )
    with pytest.raises(TranscriptionError) as excinfo:
        list(strat.transcribe(audio))
    msg = str(excinfo.value)
    assert "AppleSTT binary not found" in msg
    # Hint must point to a build path or toolchain.
    assert "build" in msg.lower()


def test_nonzero_exit_surfaces_stderr(tmp_path):
    audio = _make_audio_file(tmp_path)
    # Write a script that prints a valid line then exits non-zero with
    # something on stderr.
    script_path = tmp_path / "AppleSTT-fail.sh"
    script_path.write_text(
        "#!/usr/bin/env bash\n"
        "echo '{\"type\":\"partial\",\"text\":\"hi\",\"timestamp_ms\":0,\"latency_ms_from_audio_start\":1,\"confidence\":null,\"engine_metadata\":{}}'\n"
        'echo "AppleSTT error: speech authorization denied" >&2\n'
        "exit 10\n",
        encoding="utf-8",
    )
    st = script_path.stat()
    script_path.chmod(st.st_mode | stat.S_IEXEC | stat.S_IXGRP | stat.S_IXOTH)

    strat = AppleSpeechTranscriberStrategy(binary_path=script_path)
    with pytest.raises(TranscriptionError) as excinfo:
        list(strat.transcribe(audio))
    msg = str(excinfo.value)
    assert "exited with code 10" in msg
    assert "authorization denied" in msg


def test_malformed_json_raises_transcription_error(tmp_path):
    audio = _make_audio_file(tmp_path)
    fake = _write_fake_binary(
        tmp_path,
        stdout_lines=[
            "this is not json",
        ],
    )
    strat = AppleSpeechTranscriberStrategy(binary_path=fake)
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
    strat = AppleSpeechTranscriberStrategy(binary_path=fake)
    with pytest.raises(TranscriptionError, match="malformed event"):
        list(strat.transcribe(audio))


# ---------------------------------------------------------------------------
# Streaming / backpressure behavior
# ---------------------------------------------------------------------------


def test_events_yield_lazily(tmp_path):
    """Consumer should be able to read events one at a time without the
    wrapper buffering the full stdout. We assert this by reading just the
    first event and confirming it's an event (not a full list)."""
    audio = _make_audio_file(tmp_path)
    fake = _write_fake_binary(
        tmp_path,
        stdout_lines=[
            '{"type":"partial","text":"a","timestamp_ms":10,'
            '"latency_ms_from_audio_start":12,"confidence":null,'
            '"engine_metadata":{}}',
            '{"type":"partial","text":"a b","timestamp_ms":40,'
            '"latency_ms_from_audio_start":45,"confidence":null,'
            '"engine_metadata":{}}',
            '{"type":"final","text":"a b c","timestamp_ms":100,'
            '"latency_ms_from_audio_start":120,"confidence":null,'
            '"engine_metadata":{}}',
        ],
    )

    strat = AppleSpeechTranscriberStrategy(binary_path=fake)
    iterator = iter(strat.transcribe(audio))
    first = next(iterator)
    assert first.stability == "partial"
    assert first.text == "a"
    # Drain the rest so the subprocess exits cleanly.
    rest = list(iterator)
    assert len(rest) == 2


# ---------------------------------------------------------------------------
# Registry integration
# ---------------------------------------------------------------------------


def test_registry_registers_both_apple_strategies():
    from voice_lab import VoiceEngineLab

    engine = VoiceEngineLab.load()
    names = set(engine.list_strategies())
    assert "apple_speech_transcriber" in names
    assert "apple_sfspeechrecognizer_vocab" in names


def test_registry_returns_real_implementations_not_stubs():
    """The strategies must be the real subprocess-wrapper classes."""
    from voice_lab import VoiceEngineLab

    engine = VoiceEngineLab.load()
    st = engine.get_strategy("apple_speech_transcriber")
    sfsr = engine.get_strategy("apple_sfspeechrecognizer_vocab")
    assert isinstance(st, AppleSpeechTranscriberStrategy)
    assert isinstance(sfsr, AppleSFSpeechRecognizerStrategy)


# ---------------------------------------------------------------------------
# Real binary smoke check (skipped unless built)
# ---------------------------------------------------------------------------


def _real_binary_path() -> Path:
    """Same resolution rule as the strategy."""
    override = os.environ.get("APPLE_STT_BIN")
    if override:
        return Path(override).expanduser()
    return (
        Path(__file__).resolve().parents[2]
        / "native"
        / "apple"
        / "AppleSTT"
        / ".build"
        / "release"
        / "AppleSTT"
    )


@pytest.mark.skipif(
    not _real_binary_path().exists(),
    reason=(
        "AppleSTT release binary not built. Run "
        "voice-engine/native/apple/build.sh first."
    ),
)
def test_real_binary_responds_to_help():
    """If the Swift CLI has been built, --help should exit 0 and print usage
    to stderr. End-to-end transcription is not exercised here because it
    requires audio fixtures + speech-recognition authorization."""
    import subprocess

    result = subprocess.run(
        [str(_real_binary_path()), "--help"],
        capture_output=True,
        text=True,
    )
    assert result.returncode == 0
    # Usage goes to stderr.
    assert "AppleSTT" in (result.stderr + result.stdout)
