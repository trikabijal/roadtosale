"""voice_lab.ingestion — pull external transcripts and convert them into
the lab's script YAML schema.

Public surface:
- TranscriptSegment             a raw {text, start_seconds, duration_seconds} record
- fetch_transcript(video_id)    pull a transcript from YouTube via youtube-transcript-api
- TranscriptFetchError          base class for ingestion failures
- TranscriptUnavailableError    transcript disabled / not present / age-restricted
- build_script(...)             convert raw segments into the script YAML dict shape
"""

from __future__ import annotations

from voice_lab.ingestion.script_builder import (
    BuiltScript,
    build_script,
    detect_step_boundaries,
)
from voice_lab.ingestion.youtube import (
    TranscriptFetchError,
    TranscriptSegment,
    TranscriptUnavailableError,
    fetch_transcript,
)

__all__ = [
    "TranscriptSegment",
    "TranscriptFetchError",
    "TranscriptUnavailableError",
    "fetch_transcript",
    "BuiltScript",
    "build_script",
    "detect_step_boundaries",
]
