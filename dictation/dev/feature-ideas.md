# Just Talk — feature backlog (ideas, not yet PRD'd)

Rough ideas captured during strategy sessions. Promote to a PRD (`dev/tasks/`) before building.

## Proper-noun → custom-vocab prompt

**Idea (Bijal, 2026-07):** Most recognition errors are on *proper nouns* (names, brands, places,
product/drug/legal terms) — exactly the words a general STT model spells wrong. As soon as we detect
proper nouns in a dictation, offer to add them to the user's custom vocabulary so future dictations
spell them right.

**Why it's strong**
- Custom vocabulary is already our differentiator (bias into WhisperKit / the recognizer). This makes
  it *self-building* instead of a settings chore nobody opens.
- Proper nouns are the highest-value vocab entries and the most frequent error source.
- Fully on-device: Apple's **NaturalLanguage** framework (`NLTagger` with `.nameType` /
  `.lexicalClass`) tags proper nouns on-device, for free — fits the privacy model, no cloud.

**Mechanism**
1. After transcription (or after cleanup), run `NLTagger` over the text → extract proper nouns
   (personal names, org names, places) not already in custom vocab.
2. Surface a **non-intrusive, batched** suggestion — *not* a modal per sentence (annoying). e.g. a
   subtle "Add to dictionary: Bijal · AuditPro · Ahmedabad?" nudge after the paste, or a periodic
   "we noticed these names" review.
3. Accepted terms feed `setVocabularyBias` → better spelling next time.

**Open questions**
- Dedup + confidence threshold (don't suggest every capitalized word).
- Where the UX lives (post-paste HUD affordance vs a periodic review sheet).
- Shared across platforms: the NER + vocab store belong in the **shared contract** so Mac + the iOS
  keyboard (Trisha) both benefit — see [[justtalk-ios-keyboard-trisha]] concept.
- Indic proper nouns: NLTagger coverage for Hindi/Gujarati scripts needs verification.
