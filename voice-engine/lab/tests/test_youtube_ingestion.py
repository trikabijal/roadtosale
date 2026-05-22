"""Tests for the YouTube fetcher and the ingest CLI plumbing.

We mock youtube-transcript-api entirely so these tests are network-free
and deterministic. The fetcher contract:

    fetch_transcript(video_id) -> list[TranscriptSegment]

We verify:
- Happy-path fetch returns normalized segments sorted by start time.
- "Transcripts disabled" -> TranscriptUnavailableError.
- Age-restricted / unavailable -> TranscriptUnavailableError.
- Network blowups -> TranscriptFetchError.
- Empty / blank text segments are dropped.
- The ingest_one() pipeline writes a script YAML that loads cleanly and
  carries the required source.* fields.
"""

from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
from typing import Any

import pytest
import yaml

from voice_lab.ingestion.cli import (
    ManifestSource,
    _load_manifest,
    ingest_one,
    run_ingest_youtube,
)
from voice_lab.ingestion.script_builder import load_cue_index
from voice_lab.ingestion.youtube import (
    TranscriptFetchError,
    TranscriptSegment,
    TranscriptUnavailableError,
    fetch_transcript,
)


# ---------------------------------------------------------------------------
# A minimal stand-in for youtube_transcript_api so we never touch the network.
# We mimic the new API surface: `api.fetch(video_id, languages=...)` returns
# an object with `.to_raw_data()`.
# ---------------------------------------------------------------------------


@dataclass
class _FakeFetched:
    raw: list[dict]

    def to_raw_data(self) -> list[dict]:
        return list(self.raw)


class _HappyApi:
    def __init__(self, raw: list[dict]) -> None:
        self._raw = raw

    def fetch(self, video_id: str, languages: Any = None) -> _FakeFetched:  # noqa: ARG002
        return _FakeFetched(self._raw)


class _RaisingApi:
    def __init__(self, exc: BaseException) -> None:
        self._exc = exc

    def fetch(self, video_id: str, languages: Any = None) -> _FakeFetched:  # noqa: ARG002
        raise self._exc


def test_fetch_transcript_happy_path_returns_sorted_segments():
    raw = [
        {"text": "second snippet", "start": 5.0, "duration": 2.0},
        {"text": "first snippet", "start": 0.0, "duration": 3.0},
        {"text": "", "start": 8.0, "duration": 1.0},  # should be dropped
    ]
    segments = fetch_transcript("abc12345678", api=_HappyApi(raw))
    assert len(segments) == 2
    assert segments[0].text == "first snippet"
    assert segments[0].start_seconds == 0.0
    assert segments[1].text == "second snippet"
    assert segments[1].start_seconds == 5.0


def test_fetch_transcript_transcripts_disabled_raises_unavailable():
    from youtube_transcript_api import _errors as yt_errors

    # TranscriptsDisabled wants a WATCH_URL-formatted message in some
    # versions; construct it via the canonical method.
    exc = yt_errors.TranscriptsDisabled("vid")
    with pytest.raises(TranscriptUnavailableError):
        fetch_transcript("vid", api=_RaisingApi(exc))


def test_fetch_transcript_age_restricted_raises_unavailable():
    from youtube_transcript_api import _errors as yt_errors

    exc = yt_errors.AgeRestricted("vid")
    with pytest.raises(TranscriptUnavailableError):
        fetch_transcript("vid", api=_RaisingApi(exc))


def test_fetch_transcript_request_blocked_raises_fetch_error():
    from youtube_transcript_api import _errors as yt_errors

    exc = yt_errors.RequestBlocked("vid")
    with pytest.raises(TranscriptFetchError) as ei:
        fetch_transcript("vid", api=_RaisingApi(exc))
    # Should NOT be the unavailable subclass — this is transient.
    assert not isinstance(ei.value, TranscriptUnavailableError)


def test_fetch_transcript_random_exception_wrapped_as_fetch_error():
    with pytest.raises(TranscriptFetchError):
        fetch_transcript("vid", api=_RaisingApi(RuntimeError("boom")))


def test_fetch_transcript_rejects_empty_video_id():
    with pytest.raises(TranscriptFetchError):
        fetch_transcript("")


# ---------------------------------------------------------------------------
# CLI / ingest_one() integration tests.
# ---------------------------------------------------------------------------


_LAB_ROOT = Path(__file__).resolve().parents[1]
_WORKFLOW_CUES = _LAB_ROOT / "cue-packs" / "universal_workflow_cues.yaml"


def _fake_fetcher_for(transcript: list[TranscriptSegment]):
    """Build a `transcript_fetcher` callable usable by ingest_one()."""

    def _fake(video_id: str) -> list[TranscriptSegment]:  # noqa: ARG001
        return list(transcript)

    return _fake


def _dealer_walkaround_transcript() -> list[TranscriptSegment]:
    return [
        TranscriptSegment(
            text="Hi Sarah, welcome to Riverside Honda. My name is Marcus.",
            start_seconds=0.0,
            duration_seconds=4.0,
        ),
        TranscriptSegment(
            text="Can I grab you a coffee or water before we head out?",
            start_seconds=4.0,
            duration_seconds=3.0,
        ),
        TranscriptSegment(
            text="So tell me — mostly school runs during the week?",
            start_seconds=8.0,
            duration_seconds=3.0,
        ),
        TranscriptSegment(
            text="And highway commute most mornings, looking for better mileage.",
            start_seconds=12.0,
            duration_seconds=3.0,
        ),
        TranscriptSegment(
            text="Let me show you around — Honda Sensing 360+ is standard.",
            start_seconds=20.0,
            duration_seconds=3.0,
        ),
        TranscriptSegment(
            text="Inside you've got wireless Apple CarPlay so your phone connects without a cable.",
            start_seconds=24.0,
            duration_seconds=4.0,
        ),
        TranscriptSegment(
            text="Let me introduce you to David Chen, our finance manager.",
            start_seconds=40.0,
            duration_seconds=3.0,
        ),
    ]


def test_ingest_one_writes_valid_script_yaml(tmp_path: Path):
    cue_index = load_cue_index(_WORKFLOW_CUES, [])
    src = ManifestSource(
        video_id="abc12345678",
        title="Test walkaround",
        url="https://www.youtube.com/watch?v=abc12345678",
        target_trim_id="honda.cr-v-hybrid-awd.2026.sport-touring",
        notes=None,
        fair_use_note="Test fair use note.",
    )
    result = ingest_one(
        src,
        cue_index=cue_index,
        output_dir=tmp_path,
        force=False,
        transcript_fetcher=_fake_fetcher_for(_dealer_walkaround_transcript()),
    )

    assert result.status == "ingested"
    assert result.output_path is not None
    assert result.output_path.exists()

    loaded = yaml.safe_load(result.output_path.read_text())
    assert loaded["id"] == "youtube_abc12345678"
    assert loaded["target_trim_id"] == src.target_trim_id
    assert loaded["source"]["type"] == "youtube_transcript"
    assert loaded["source"]["video_id"] == src.video_id
    assert loaded["source"]["url"] == src.url
    assert loaded["source"]["fair_use_note"] == src.fair_use_note
    assert loaded["source"]["fetched_at"].endswith("Z")
    assert isinstance(loaded["segments"], list)
    assert loaded["segments"], "expected at least one segment"
    assert "negative_cues" in loaded


def test_ingest_one_is_idempotent(tmp_path: Path):
    cue_index = load_cue_index(_WORKFLOW_CUES, [])
    src = ManifestSource(
        video_id="vid0000001",
        title="Idempotency test",
        url="https://www.youtube.com/watch?v=vid0000001",
        target_trim_id="honda.cr-v-hybrid-awd.2026.sport-touring",
        notes=None,
        fair_use_note="Test note.",
    )

    first = ingest_one(
        src,
        cue_index=cue_index,
        output_dir=tmp_path,
        force=False,
        transcript_fetcher=_fake_fetcher_for(_dealer_walkaround_transcript()),
    )
    assert first.status == "ingested"

    second = ingest_one(
        src,
        cue_index=cue_index,
        output_dir=tmp_path,
        force=False,
        transcript_fetcher=_fake_fetcher_for(_dealer_walkaround_transcript()),
    )
    assert second.status == "skipped"

    # --force overrides the skip
    third = ingest_one(
        src,
        cue_index=cue_index,
        output_dir=tmp_path,
        force=True,
        transcript_fetcher=_fake_fetcher_for(_dealer_walkaround_transcript()),
    )
    assert third.status == "ingested"


def test_ingest_one_records_no_transcript_for_disabled_video(tmp_path: Path):
    cue_index = load_cue_index(_WORKFLOW_CUES, [])
    src = ManifestSource(
        video_id="disabled001",
        title="Disabled",
        url="https://www.youtube.com/watch?v=disabled001",
        target_trim_id="honda.cr-v-hybrid-awd.2026.sport-touring",
        notes=None,
        fair_use_note="Test note.",
    )

    def _raise(_video_id: str) -> list[TranscriptSegment]:
        raise TranscriptUnavailableError("transcripts disabled")

    result = ingest_one(
        src,
        cue_index=cue_index,
        output_dir=tmp_path,
        force=False,
        transcript_fetcher=_raise,
    )
    assert result.status == "no_transcript"
    assert result.output_path is None
    # No file should have been written.
    assert not list(tmp_path.glob("*.yaml"))


def test_ingest_one_records_fetch_error_for_network_failure(tmp_path: Path):
    cue_index = load_cue_index(_WORKFLOW_CUES, [])
    src = ManifestSource(
        video_id="blocked0001",
        title="Blocked",
        url="https://www.youtube.com/watch?v=blocked0001",
        target_trim_id="honda.cr-v-hybrid-awd.2026.sport-touring",
        notes=None,
        fair_use_note="Test note.",
    )

    def _raise(_video_id: str) -> list[TranscriptSegment]:
        raise TranscriptFetchError("IP blocked")

    result = ingest_one(
        src,
        cue_index=cue_index,
        output_dir=tmp_path,
        force=False,
        transcript_fetcher=_raise,
    )
    assert result.status == "fetch_error"
    assert result.output_path is None


def test_run_ingest_youtube_processes_full_manifest(tmp_path: Path):
    manifest_path = tmp_path / "manifest.yaml"
    manifest_path.write_text(
        yaml.safe_dump(
            {
                "sources": [
                    {
                        "video_id": "vidone00001",
                        "title": "One",
                        "url": "https://www.youtube.com/watch?v=vidone00001",
                        "target_trim_id": "honda.cr-v-hybrid-awd.2026.sport-touring",
                        "fair_use_note": "Test.",
                    },
                    {
                        "video_id": "vidtwo00002",
                        "title": "Two",
                        "url": "https://www.youtube.com/watch?v=vidtwo00002",
                        "target_trim_id": "honda.cr-v-hybrid-awd.2026.sport-touring",
                        "fair_use_note": "Test.",
                    },
                ]
            }
        )
    )
    output_dir = tmp_path / "out"

    results = run_ingest_youtube(
        manifest_path=manifest_path,
        output_dir=output_dir,
        workflow_cues_path=_WORKFLOW_CUES,
        feature_catalog_root=tmp_path / "does-not-exist",
        force=False,
        transcript_fetcher=_fake_fetcher_for(_dealer_walkaround_transcript()),
    )

    assert len(results) == 2
    assert all(r.status == "ingested" for r in results)
    assert (output_dir / "youtube_vidone00001.yaml").exists()
    assert (output_dir / "youtube_vidtwo00002.yaml").exists()


def test_load_manifest_rejects_entry_missing_required_fields(tmp_path: Path):
    bad = tmp_path / "bad.yaml"
    bad.write_text(yaml.safe_dump({"sources": [{"video_id": "x", "title": "no trim"}]}))
    with pytest.raises(ValueError):
        _load_manifest(bad)
