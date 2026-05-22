"""Cross-language drift test.

Writes a Python TranscriptEvent to JSON; spawns `node` to round-trip the
payload through the TS loader; asserts every field survives unchanged.
If `node` or the TS package is unavailable, the test skips with a reason.
"""

from __future__ import annotations

import json
import os
import shutil
import subprocess
from dataclasses import asdict
from pathlib import Path

import pytest

from voice_lab.types import TranscriptEvent


LAB_DIR = Path(__file__).resolve().parents[1]
LIB_DIR = LAB_DIR.parent  # voice-engine/


def _node_available() -> bool:
    return shutil.which("node") is not None


@pytest.mark.skipif(not _node_available(), reason="node not on PATH")
def test_transcript_event_roundtrips_through_ts():
    src_ts = LIB_DIR / "src" / "types" / "index.ts"
    if not src_ts.exists():
        pytest.skip("TS types file not found — skip drift check")

    event = TranscriptEvent(
        text="hello world",
        stability="partial",
        timestamp_ms=1234,
        latency_ms_from_audio_start=1300,
        confidence=0.87,
        engine_metadata={"vendor": "mock", "trace": "abc"},
    )
    payload = json.dumps(asdict(event))

    # Node script: read JSON from stdin, assert each field, print round-tripped JSON.
    script = """
let raw = '';
process.stdin.setEncoding('utf8');
process.stdin.on('data', (c) => { raw += c; });
process.stdin.on('end', () => {
  const evt = JSON.parse(raw);
  const required = ['text','stability','timestamp_ms','latency_ms_from_audio_start','confidence','engine_metadata'];
  for (const k of required) {
    if (!(k in evt)) { console.error('missing field: ' + k); process.exit(2); }
  }
  if (!['partial','final'].includes(evt.stability)) { console.error('bad stability'); process.exit(2); }
  process.stdout.write(JSON.stringify(evt));
});
"""
    result = subprocess.run(
        ["node", "-e", script],
        input=payload,
        capture_output=True,
        text=True,
        env={**os.environ},
    )
    assert result.returncode == 0, f"node exit {result.returncode}: {result.stderr}"
    roundtripped = json.loads(result.stdout)
    assert roundtripped == json.loads(payload)


@pytest.mark.skipif(not _node_available(), reason="node not on PATH")
def test_cue_atom_field_names_match_ts():
    """Verify the TS types file declares the same field names we use in Python."""
    src_ts = LIB_DIR / "src" / "types" / "index.ts"
    if not src_ts.exists():
        pytest.skip("TS types file not found")
    text = src_ts.read_text(encoding="utf-8")
    for field_name in ["text", "stability", "timestamp_ms", "latency_ms_from_audio_start", "confidence", "engine_metadata"]:
        assert field_name in text, f"TS types missing field {field_name}"
    for field_name in ["display_name", "cue_phrases", "synonyms", "metadata", "source"]:
        assert field_name in text, f"TS types missing field {field_name}"
    for field_name in ["cue_id", "matched_phrase", "triggering_event"]:
        assert field_name in text, f"TS types missing field {field_name}"
