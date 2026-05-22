"""YouTube transcript fetcher.

Thin wrapper around the `youtube-transcript-api` package. The wrapper exists
so the rest of the ingestion pipeline depends on a stable, package-local
record type (`TranscriptSegment`) and a small error taxonomy
(`TranscriptUnavailableError` for known "no transcript" outcomes,
`TranscriptFetchError` for everything else).

Failure modes we surface as `TranscriptUnavailableError`:
- Transcripts disabled on the video
- No transcript in the requested language
- Age-restricted video
- Generic "could not retrieve transcript"
- Invalid video id

Failure modes we surface as `TranscriptFetchError`:
- Network / HTTP errors
- Rate-limit / IP-block
- Anything else from the underlying client

The CLI catches both and records the failure mode in the run log; callers
should treat both as soft failures (skip the video, continue the run).
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Iterable


@dataclass(frozen=True)
class TranscriptSegment:
    """A single transcript snippet as surfaced by YouTube.

    Note: `duration_seconds` reflects how long the snippet stays on screen,
    not the duration of the spoken text. Overlap between adjacent snippets
    is possible. We pass the field through unchanged.
    """

    text: str
    start_seconds: float
    duration_seconds: float


class TranscriptFetchError(Exception):
    """Generic fetch failure — network, rate-limit, or unexpected client error."""


class TranscriptUnavailableError(TranscriptFetchError):
    """Transcript is known not to be available for this video.

    Distinct from `TranscriptFetchError` so callers can decide whether to
    retry (transient) or skip permanently (no transcript will ever come).
    """


def _coerce_segments(raw_data: Iterable[dict]) -> list[TranscriptSegment]:
    out: list[TranscriptSegment] = []
    for entry in raw_data:
        text = str(entry.get("text", "")).strip()
        if not text:
            continue
        out.append(
            TranscriptSegment(
                text=text,
                start_seconds=float(entry.get("start", 0.0)),
                duration_seconds=float(entry.get("duration", 0.0)),
            )
        )
    return out


def fetch_transcript(
    video_id: str,
    languages: tuple[str, ...] = ("en",),
    *,
    api: object | None = None,
) -> list[TranscriptSegment]:
    """Fetch the transcript for a YouTube video.

    Parameters
    ----------
    video_id:
        The 11-character YouTube video id (the `v=` query parameter).
    languages:
        Preference list of BCP-47 language codes. Defaults to English only.
    api:
        Optional pre-constructed `YouTubeTranscriptApi` instance. Mostly a
        test seam — production callers should leave this as `None` and let
        the function instantiate its own client.

    Returns
    -------
    A list of `TranscriptSegment` records sorted by `start_seconds`.

    Raises
    ------
    TranscriptUnavailableError:
        Transcript is known not to exist for this video.
    TranscriptFetchError:
        Any other failure (network, rate limit, unexpected error).
    """
    if not video_id or not isinstance(video_id, str):
        raise TranscriptFetchError(f"video_id must be a non-empty string, got {video_id!r}")

    try:
        # Import lazily so the package is only required when ingestion runs.
        from youtube_transcript_api import YouTubeTranscriptApi
        from youtube_transcript_api import _errors as yt_errors
    except ImportError as exc:  # pragma: no cover - import-time error
        raise TranscriptFetchError(
            "youtube-transcript-api is not installed. "
            "Add it to the lab's pyproject.toml dependencies."
        ) from exc

    client = api if api is not None else YouTubeTranscriptApi()

    # IMPORTANT: in youtube-transcript-api 1.x every concrete error inherits
    # from CouldNotRetrieveTranscript. So `except` order matters — we MUST
    # catch the transient subclasses (IpBlocked, RequestBlocked, ...) BEFORE
    # the general "no transcript will ever exist" group, otherwise the
    # general catch swallows them.
    try:
        fetched = client.fetch(video_id, languages=list(languages))  # type: ignore[attr-defined]
    except (
        yt_errors.IpBlocked,
        yt_errors.RequestBlocked,
        yt_errors.YouTubeRequestFailed,
        yt_errors.YouTubeDataUnparsable,
        yt_errors.PoTokenRequired,
    ) as exc:
        raise TranscriptFetchError(
            f"Transient fetch failure for {video_id}: {type(exc).__name__}"
        ) from exc
    except (
        yt_errors.TranscriptsDisabled,
        yt_errors.NoTranscriptFound,
        yt_errors.AgeRestricted,
        yt_errors.VideoUnavailable,
        yt_errors.VideoUnplayable,
        yt_errors.InvalidVideoId,
        yt_errors.NotTranslatable,
        yt_errors.TranslationLanguageNotAvailable,
        yt_errors.CouldNotRetrieveTranscript,
    ) as exc:
        raise TranscriptUnavailableError(
            f"Transcript unavailable for {video_id}: {type(exc).__name__}"
        ) from exc
    except yt_errors.YouTubeTranscriptApiException as exc:
        raise TranscriptFetchError(
            f"Unexpected youtube-transcript-api error for {video_id}: "
            f"{type(exc).__name__}: {exc}"
        ) from exc
    except Exception as exc:  # final guard — anything else (HTTP, etc.)
        raise TranscriptFetchError(
            f"Unexpected error fetching {video_id}: {type(exc).__name__}: {exc}"
        ) from exc

    # The new API returns a FetchedTranscript object with `.to_raw_data()`.
    # In tests we may pass back a plain list to keep the seam simple.
    if hasattr(fetched, "to_raw_data"):
        raw = fetched.to_raw_data()
    else:
        raw = fetched  # already a list of dicts (test seam)

    segments = _coerce_segments(raw)
    segments.sort(key=lambda s: s.start_seconds)
    return segments
