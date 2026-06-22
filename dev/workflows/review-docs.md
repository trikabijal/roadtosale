# Workflow: Review Docs for Clarity

Make documentation read cleanly — plain enough for a newcomer, precise enough for a developer — without changing what it says. Use this after docs are written or refreshed, when the content is correct but the language is dense, abstract, or full of AI-generated filler.

## When to use

- After a docs-generation or docs-refresh pass (e.g. `architecture.md` / `api.md` / `flows.md` / `build.md` / test plans).
- When prose is technically accurate but hard to read: long sentences, vague abstraction, grandiose phrasing, undefined jargon.
- Before handing a repo to a new team member.

This is a **language/clarity edit only**. It does not fix wrong facts (that's a content review) or restructure docs.

## Approach: one agent per docs folder, in parallel

Run the review as a fan-out — one agent per `docs/` folder (top-level `docs/` plus each module's `docs/`). Folders don't overlap, so the agents never touch the same files and can all run at once.

Each agent reviews **every `.md` file in its folder** and edits in place for clarity.

> Sequencing caveat: if another wave is actively writing the same docs (e.g. agents adding a `build.md` or a link line to `architecture.md`), let it finish and commit first. Two agents editing one file at the same time clobber each other.

## The clarity rubric

Apply this to every file. The goal is "layman + developer": a non-expert can follow the shape of it, and a developer trusts the detail.

**Do:**
- Use plain language and short sentences. Prefer active voice.
- Define jargon and acronyms on first use.
- Lead with the point, then explain. Keep it skimmable.
- Replace vague abstraction with concrete, specific statements (name the file, the function, the actual behavior).
- Cut filler and grandiose phrasing — words like "seamlessly", "robust", "leverages", "orchestrates", "powerful", "simply", "comprehensive" that add no information.

**Don't (hard guardrails):**
- Never change a technical fact, number, file path, command name, or code block.
- Never alter link targets, tables, or section structure.
- Never add new claims the source didn't make.
- Never remove a cross-link.

If the rewrite would change meaning, stop and leave it — flag it for a content review instead.

## Verify after editing

- **Links still resolve.** Re-check every relative Markdown link (a path resolver, not a naive string match — BSD `realpath` lacks `-m`; use Python `os.path.normpath` + `os.path.isfile`).
- **Scope stayed docs-only.** `git status` should show only `.md` files changed — no source or test files.
- **Diff is prose-only.** Spot-check that edits changed wording, not facts: paths, code, numbers, and headings are untouched.

## Output

- Edited `.md` files in place.
- A short per-folder summary: files touched, and any spots flagged for a separate content review (where the language was unclear *because the underlying fact was unclear*).
- Commit as a docs-only change, separate from content or code commits.
