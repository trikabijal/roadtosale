"""Apple SFSpeechRecognizer strategy (with dealership-vocab biasing).

The new ``SpeechTranscriber`` API does NOT support custom vocabulary —
this is documented in the research brief
(``dev/docs/ROAD_TO_SALE_AUDIO_RESEARCH_APPLE.md`` section 4). The legacy
``SFSpeechRecognizer`` accepts an array of contextual strings that bias
the recognizer toward those terms, which is what we need for dealership
features like "Honda Sensing 360+", "CR-V Hybrid AWD", "HondaLink", etc.

This strategy spawns the same AppleSTT Swift CLI as
``AppleSpeechTranscriberStrategy``, but with
``--mode sfspeech_recognizer --vocab <path>``.

Vocabulary file: defaults to
``voice-engine/lab/cue-packs/dealership_vocabulary.txt`` (one term per
line, blank lines and ``#`` comments allowed). Override with a constructor
argument or the ``APPLE_STT_VOCAB`` env var.
"""

from __future__ import annotations

import os
from pathlib import Path
from typing import Iterable

from voice_lab.strategies.apple_speech_transcriber import (
    _run_apple_stt,
)
from voice_lab.strategies.base import TranscriptionStrategy
from voice_lab.types import TranscriptEvent, TranscriptionError


_DEFAULT_VOCAB = (
    Path(__file__).resolve().parents[3]
    / "cue-packs"
    / "dealership_vocabulary.txt"
)


def _resolve_vocab(path: Path | None) -> Path:
    if path is not None:
        return Path(path).expanduser()
    override = os.environ.get("APPLE_STT_VOCAB")
    if override:
        return Path(override).expanduser()
    return _DEFAULT_VOCAB


class AppleSFSpeechRecognizerStrategy(TranscriptionStrategy):
    """SFSpeechRecognizer strategy with contextualStrings vocab biasing."""

    name = "apple_sfspeechrecognizer_vocab"

    def __init__(
        self,
        *,
        binary_path: Path | None = None,
        vocab_path: Path | None = None,
        locale: str = "en-US",
        partials: bool = True,
    ) -> None:
        self._binary_path = Path(binary_path) if binary_path else None
        self._vocab_path = vocab_path
        self._locale = locale
        self._partials = partials

    def _build_argv(self, binary: Path, audio_path: Path) -> list[str]:
        vocab = _resolve_vocab(self._vocab_path)
        if not vocab.exists():
            raise TranscriptionError(
                f"{self.name}: vocab file not found at {vocab}. Create it or "
                "set APPLE_STT_VOCAB to point at an existing file."
            )
        return [
            str(binary),
            "--file",
            str(audio_path),
            "--mode",
            "sfspeech_recognizer",
            "--locale",
            self._locale,
            "--partials",
            "true" if self._partials else "false",
            "--vocab",
            str(vocab),
        ]

    def transcribe(self, audio_path: Path) -> Iterable[TranscriptEvent]:
        return _run_apple_stt(
            argv_builder=lambda bin_path: self._build_argv(bin_path, audio_path),
            binary_path=self._binary_path,
            audio_path=audio_path,
            engine_label=self.name,
        )
