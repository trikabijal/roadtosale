"""Tests for the SherpaOnnxSTT subprocess wrapper.

Mirrors ``test_whisperkit_strategy.py`` / ``test_apple_strategy.py``: the actual
JVM CLI binary is replaced with a tiny POSIX shell-script fake that emits a known
JSONL transcript on stdout. That keeps the tests:

  - hermetic (no sherpa-onnx ONNX model download, no Java toolchain, no Android
    device/emulator)
  - cross-platform for the wrapper-level assertions
  - independent of the JVM build

The wrapper-level contract (argv built, JSONL parsed, errors surfaced, lazy
yield) is what we assert; the engine's *accuracy* is the manual benchmark lane.

sherpa_onnx is the **same library Android ships** and is structurally identical
to WhisperKit; this suite is the parity counterpart so any divergence is a bug.
"""

from __future__ import annotations

import os
import stat
from pathlib import Path
from typing import List

import pytest

from voice_lab.strategies.sherpa_onnx import SherpaOnnxStrategy
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
    """Write a tiny POSIX shell script that emulates the SherpaOnnxSTT CLI.

    - Optionally records its argv to ``log_argv_path`` so the tests can
      assert the wrapper passes the right flags.
    - Emits ``stdout_lines`` verbatim on stdout.
    - Exits with ``exit_code``.
    """
    script_path = tmp_path / "SherpaOnnxSTT-fake.sh"
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


def test_sherpa_onnx_strategy_parses_jsonl(tmp_path):
    audio = _make_audio_file(tmp_path)
    fake = _write_fake_binary(
        tmp_path,
        stdout_lines=[
            '{"type":"partial","text":"hello","timestamp_ms":120,'
            '"latency_ms_from_audio_start":135,"confidence":0.87,'
            '"engine_metadata":{"engine":"sherpa_onnx",'
            '"model":"whisper-tiny-en","is_volatile":true,'
            '"simulated":true}}',
            '{"type":"final","text":"hello world","timestamp_ms":520,'
            '"latency_ms_from_audio_start":560,"confidence":0.94,'
            '"engine_metadata":{"engine":"sherpa_onnx",'
            '"model":"whisper-tiny-en","is_volatile":false,'
            '"segment_start_s":0.52,"segment_end_s":1.10}}',
        ],
    )

    strat = SherpaOnnxStrategy(binary_path=fake)
    events = list(strat.transcribe(audio))

    assert len(events) == 2
    assert events[0].stability == "partial"
    assert events[0].text == "hello"
    assert events[0].timestamp_ms == 120
    assert events[0].latency_ms_from_audio_start == 135
    assert events[0].confidence == pytest.approx(0.87)
    assert events[0].engine_metadata["engine"] == "sherpa_onnx"
    assert events[0].engine_metadata["is_volatile"] is True
    assert events[0].engine_metadata["simulated"] is True

    assert events[1].stability == "final"
    assert events[1].text == "hello world"
    assert events[1].latency_ms_from_audio_start == 560
    assert events[1].confidence == pytest.approx(0.94)
    assert events[1].engine_metadata["segment_end_s"] == pytest.approx(1.10)


# ---------------------------------------------------------------------------
# argv assertions
# ---------------------------------------------------------------------------


def test_sherpa_onnx_passes_expected_argv(tmp_path):
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

    strat = SherpaOnnxStrategy(binary_path=fake, model="whisper-base-en")
    list(strat.transcribe(audio))

    args = argv_log.read_text(encoding="utf-8").splitlines()
    # Expect: --file <path> --model whisper-base-en --partials true
    assert "--file" in args
    assert str(audio) in args
    assert "--model" in args
    assert "whisper-base-en" in args
    assert "--partials" in args
    assert "true" in args


def test_sherpa_onnx_default_model_used_when_env_unset(tmp_path, monkeypatch):
    monkeypatch.delenv("SHERPA_ONNX_MODEL", raising=False)
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

    strat = SherpaOnnxStrategy(binary_path=fake)
    list(strat.transcribe(audio))
    args = argv_log.read_text(encoding="utf-8").splitlines()
    idx = args.index("--model")
    assert args[idx + 1] == "whisper-tiny-en"


def test_sherpa_onnx_env_var_overrides_default_model(tmp_path, monkeypatch):
    monkeypatch.setenv("SHERPA_ONNX_MODEL", "whisper-small-en")
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
    strat = SherpaOnnxStrategy(binary_path=fake)
    list(strat.transcribe(audio))
    args = argv_log.read_text(encoding="utf-8").splitlines()
    idx = args.index("--model")
    assert args[idx + 1] == "whisper-small-en"


def test_sherpa_onnx_per_instance_model_overrides_env(tmp_path, monkeypatch):
    """A per-instance ``model=`` wins over the SHERPA_ONNX_MODEL env var."""
    monkeypatch.setenv("SHERPA_ONNX_MODEL", "whisper-small-en")
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

    strat = SherpaOnnxStrategy(binary_path=fake, model="whisper-tiny-en")
    list(strat.transcribe(audio))
    args = argv_log.read_text(encoding="utf-8").splitlines()
    idx = args.index("--model")
    assert args[idx + 1] == "whisper-tiny-en"


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
    strat = SherpaOnnxStrategy(binary_path=fake, partials=False)
    list(strat.transcribe(audio))
    args = argv_log.read_text(encoding="utf-8").splitlines()
    idx = args.index("--partials")
    assert args[idx + 1] == "false"


# ---------------------------------------------------------------------------
# Error paths
# ---------------------------------------------------------------------------


def test_missing_audio_file_raises_clear_error(tmp_path):
    fake = _write_fake_binary(tmp_path, stdout_lines=[])
    strat = SherpaOnnxStrategy(binary_path=fake)
    with pytest.raises(TranscriptionError, match="audio file not found"):
        list(strat.transcribe(tmp_path / "nope.wav"))


def test_missing_binary_raises_with_build_hint(tmp_path):
    audio = _make_audio_file(tmp_path)
    strat = SherpaOnnxStrategy(binary_path=tmp_path / "does-not-exist")
    with pytest.raises(TranscriptionError) as excinfo:
        list(strat.transcribe(audio))
    msg = str(excinfo.value)
    assert "SherpaOnnxSTT binary not found" in msg
    # Hint must point to a build path or toolchain.
    assert "sherpa_onnx_build.sh" in msg
    # And the SHERPA_ONNX_BIN env override must be advertised.
    assert "SHERPA_ONNX_BIN" in msg


def test_env_bin_override_resolution(tmp_path, monkeypatch):
    """SHERPA_ONNX_BIN resolves the binary when no explicit binary_path given."""
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
    monkeypatch.setenv("SHERPA_ONNX_BIN", str(fake))

    strat = SherpaOnnxStrategy()  # no binary_path → resolves via env
    events = list(strat.transcribe(audio))
    assert len(events) == 1
    # The resolved fake binary actually ran and logged its argv.
    args = argv_log.read_text(encoding="utf-8").splitlines()
    assert "--file" in args


def test_nonzero_exit_surfaces_stderr(tmp_path):
    audio = _make_audio_file(tmp_path)
    script_path = tmp_path / "SherpaOnnxSTT-fail.sh"
    script_path.write_text(
        "#!/usr/bin/env bash\n"
        "echo '{\"type\":\"partial\",\"text\":\"hi\",\"timestamp_ms\":0,"
        '"latency_ms_from_audio_start":1,"confidence":null,'
        "\"engine_metadata\":{}}'\n"
        'echo "SherpaOnnxSTT error: model load failed" >&2\n'
        "exit 50\n",
        encoding="utf-8",
    )
    st = script_path.stat()
    script_path.chmod(st.st_mode | stat.S_IEXEC | stat.S_IXGRP | stat.S_IXOTH)

    strat = SherpaOnnxStrategy(binary_path=script_path)
    with pytest.raises(TranscriptionError) as excinfo:
        list(strat.transcribe(audio))
    msg = str(excinfo.value)
    assert "exited with code 50" in msg
    assert "model load failed" in msg


def test_malformed_json_raises_transcription_error(tmp_path):
    audio = _make_audio_file(tmp_path)
    fake = _write_fake_binary(
        tmp_path,
        stdout_lines=[
            "this is not json",
        ],
    )
    strat = SherpaOnnxStrategy(binary_path=fake)
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
    strat = SherpaOnnxStrategy(binary_path=fake)
    with pytest.raises(TranscriptionError, match="malformed event"):
        list(strat.transcribe(audio))


# ---------------------------------------------------------------------------
# Lazy yield / backpressure (P-SUB-6)
# ---------------------------------------------------------------------------


def test_events_yield_lazily(tmp_path):
    """Consumer should be able to read events one at a time without the
    wrapper buffering the full stdout."""
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

    strat = SherpaOnnxStrategy(binary_path=fake)
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


def test_registry_registers_sherpa_onnx_strategy():
    from voice_lab import VoiceEngineLab

    engine = VoiceEngineLab.load()
    names = set(engine.list_strategies())
    assert "sherpa_onnx" in names


def test_registry_returns_real_sherpa_onnx_implementation():
    """The strategy must be the real subprocess-wrapper class."""
    from voice_lab import VoiceEngineLab

    engine = VoiceEngineLab.load()
    sp = engine.get_strategy("sherpa_onnx")
    assert isinstance(sp, SherpaOnnxStrategy)


# ---------------------------------------------------------------------------
# Real binary smoke check (skipped unless built; --help only)
# ---------------------------------------------------------------------------


def _real_binary_path() -> Path:
    """Same resolution rule as the strategy."""
    override = os.environ.get("SHERPA_ONNX_BIN")
    if override:
        return Path(override).expanduser()
    return (
        Path(__file__).resolve().parents[2]
        / "native"
        / "android"
        / "SherpaOnnxSTT"
        / "build"
        / "install"
        / "SherpaOnnxSTT"
        / "bin"
        / "SherpaOnnxSTT"
    )


@pytest.mark.skipif(
    not _real_binary_path().exists(),
    reason=(
        "SherpaOnnxSTT binary not built. Run "
        "voice-engine/native/android/sherpa_onnx_build.sh first."
    ),
)
def test_real_binary_responds_to_help():
    """If the JVM CLI has been built, --help should exit 0 and print usage.
    End-to-end transcription is not exercised here because it requires a
    one-time ONNX model download."""
    import subprocess

    result = subprocess.run(
        [str(_real_binary_path()), "--help"],
        capture_output=True,
        text=True,
    )
    assert result.returncode == 0
    assert "SherpaOnnxSTT" in (result.stderr + result.stdout)
