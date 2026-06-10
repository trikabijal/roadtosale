# Dictation Miss-Log (first-tester dogfooding)

**Purpose:** Bijal is tester #1. His own usage is the dataset that tells us *which* data
the app needs to capture (and from where) before we generalize to other testers. Log what
gets missed, categorize it, and let the categories drive the vocabulary/data-capture roadmap.

**Low-friction workflow:** don't stop mid-dictation. The app stores every raw transcript +
cleaned output in the telemetry DB (searchable history). Review history in batches and
harvest misses here. One line per miss.

**Categories → fix mechanism:**

| Category | What it means | Likely fix |
|----------|---------------|------------|
| `proper-noun` | name / company / place misheard | Custom Vocabulary add → later: Contacts/Calendar harvest |
| `accent` | systematic phoneme mis-hear on common words | vocab forced-spelling; flag if frequent (→ acoustic question) |
| `jargon` | domain term (NADA, dealership, tech) misheard | Custom Vocabulary add; candidate for a shipped vocab pack |
| `punctuation` | wrong/missing punctuation or caps | cleanup prompt / rule tweak |
| `over-edit` | cleanup changed meaning or deleted real content | cleanup prompt guardrail; lower level for that app |
| `command-missed` | "new paragraph" etc. not obeyed | command grammar in cleanup pack |
| `hallucination` | text invented from silence/noise | silence-floor / junk-phrase threshold |
| `latency` | too slow to be usable | model tier / streaming |

## Log

| Date | App / context | What I said | What it produced | Category | Candidate fix |
|------|---------------|-------------|------------------|----------|---------------|
| | | | | | |

## Running tally of recurring misses (promote to a fix when count climbs)

- _e.g._ "Bijal" → "be jaal" (×?) — proper-noun, add to vocab

## Capture-source hypotheses (validate against the misses above)

These are guesses about *where* the missing data lives. Confirm each only if the log shows
that class of miss actually happens to me:

1. **Contacts** — names, company, family-relationship fields. Highest yield if proper-noun misses dominate.
2. **Calendar attendees** — recurring collaborator names.
3. **Usage frequency mining** — proper-noun-shaped tokens recurring in stored history.
4. **Just-in-time micro-prompt** — "Add 'X'? Heard it N×." one-tap.

Privacy unlock (the reason continual onboarding works here): 100% on-device — "your Mac
learns these names; nothing leaves the machine."
