# CI/CD and Configuration Changes — Team Brief

This doc summarises the structural changes we landed across the
`kazi-development → development → devops` flow while bringing the AI
photo-assessment feature stack onto SIT. **Read this before touching
`.gitlab-ci.yml` or any `application*.properties` file.**

Last updated: 2026-05-07

---

## TL;DR

| Area | Before | After |
|---|---|---|
| `.gitlab-ci.yml` | Two CI files: a half-broken one on `kazi-development`, a working build/deploy one on `devops`. | **One unified CI** on every branch. Same file across the board. |
| Stages | `build` ran on every branch (slow, no gating); `deploy` was tied to devops's CI only. | `build` runs on `kazi-development`, `devops`, `main`, MRs. `build-docker-image` and `deploy` only on `devops`. |
| Property files | Branch-specific copies; `application.properties` had different content per branch (incl. `spring.profiles.active`). | **Two-tier**: `application.properties` is identical everywhere (env-agnostic). Profile files (`application-local.properties`, `application-uat.properties`) carry env-specific overrides. |
| Active profile | Hard-coded `spring.profiles.active=local|uat` in `application.properties`. | Set externally via `SPRING_PROFILES_ACTIVE` env var. The deploy job sets `=uat`; local devs set their own. |
| AI provider keys | n/a (feature didn't exist). | Masked GitLab CI variables (`ANTHROPIC_KEY`, `OPENAI_KEY`, `GEMINI_KEY`, `GROK_KEY`), injected by the deploy job's `docker run -e`. |
| Dev / feature branch builds | Triggered every push. | **Manual trigger only** — only `devops` builds automatically; everywhere else the build sits as a Play button in the Pipelines UI. Saves runner time on WIP commits. |

---

## 1. Branch flow and gating

```
   kazi-development  →  development  →  devops  →  SIT (smartcomply.tiez.net)
   build = manual    build = manual    build → docker → deploy
                                       auto on every push
```

- **`kazi-development`** — feature/integration branch. `build` job sits as a manual Play button; click it from the Pipelines UI when you want to validate.
- **`development`** — shared trunk. Same: build is manual.
- **`devops`** — deploy branch. Every push **auto-fires** the full pipeline: `build → build-docker-image → deploy`. The `deploy` stage runs on the `smartcomply-ssh` shell runner that lives on the SIT box.
- **`main`** — reserved for future production deploy; build is manual there too.
- **Any other branch (`fix/*`, `feature/*`, MRs)** — build is manual. The pipeline is created on push but doesn't run anything until you click Play.

### When to re-enable auto-builds on dev

If multiple devs start working `development` in parallel and want pre-merge validation on every push, edit `.gitlab-ci.yml` and add `- if: $CI_COMMIT_BRANCH == "development"` above the `- when: manual` line under the `build:` job.

---

## 2. The single `.gitlab-ci.yml`

The previous setup had two separate CI files: one on `kazi-development` (modern — with `rules:`, `needs:`, image `maven:3.9-eclipse-temurin-17`), and one on `devops` (older — `image: ruby:2.7` + apt-installed Maven, no rules, did the actual SIT deploy).

We merged them into **one** file that lives on every branch. Conflict resolution at the dev → devops merge took **devops's** image base (`ruby:2.7` + apt) so the deploy runner doesn't change, plus kazi's rules/needs structure.

### Stages

| Stage | Job(s) | Where it runs | When |
|---|---|---|---|
| `build` | `build` | `smartcomply` runner | auto on `devops`; manual everywhere else |
| `package` | `build-docker-image` | `smartcomply` runner | only on `devops` |
| `deploy` | `deploy`, `seed-kia-uat-users`, `seed-kia-dealerships` | `smartcomply-ssh` runner (on SIT box) | only on `devops` (deploy auto, seeds manual) |

### Why some stages are devops-only

`build-docker-image` and `deploy` are gated to `devops` so kazi/development pushes don't:
- Overwrite the image in the GitLab registry that devops then deploys
- Restart the SIT container

The build stage *does* run everywhere it makes sense, so the WAR + LLM jar + AI tests are validated before they reach the deploy box.

### Common pitfall — `rules` mismatch

If you add a new job, make sure its `rules:` align with its `needs:` chain. The pipeline that triggered the original CI fix failed YAML validation because `package` was pulled in via a catch-all `when: manual` rule on a branch where `compile`/`test` were filtered out. **`needs:` must reference jobs whose rules also match the current pipeline context.**

---

## 3. Property files — the two-tier layout

### Before
`application.properties` had different content per branch:
- kazi: `spring.profiles.active=local`
- development: `spring.profiles.active=uat`
- devops: `spring.profiles.active=uat` plus the entire UAT config (DB creds, mail creds, AWS keys, Flyway, mail SMTP, multipart, etc.)

`application-uat.properties` only existed on devops (the others had it gitignored). Result: every merge between branches was one wrong-resolution away from clobbering UAT config.

### After
Two well-defined tiers, both tracked in git on every branch:

#### `src/main/resources/application.properties` (env-agnostic; identical everywhere)
- Postgres driver, schema, dialect, Hibernate plumbing
- `server.port=8089`
- JWT / OTP token policy (`application.security.jwt.*`, `app.jwt*`, `app.otpExpiration`)
- Flyway config (driven by `${spring.datasource.*}` from the env file)
- SMTP host/port + transport flags
- AWS region (creds + bucket are env-specific)
- File storage path
- Feature flags (`is_email_send`, `is_notification_send`, `is_API_log_save`)
- Multipart upload limits

**Does NOT contain `spring.profiles.active`** — the active profile is set externally (see §4).

#### `src/main/resources/application-{profile}.properties` (env-specific)
- DB url / user / password
- `spring.jpa.hibernate.ddl-auto`, `spring.jpa.show-sql`
- Log levels (e.g. `logging.level.org.apache.coyote`)
- Mail credentials (username/password)
- AWS S3 credentials + bucket
- `app.protocol`, `app.environmentDomain`
- AI photo-assessment toggle and LLM config

Two profile files exist today:
- `application-local.properties` — blank placeholders for sensitive values, AI **off**, intended for `=local` runs
- `application-uat.properties` — actual SIT/UAT values, AI **on**, `llm.active-provider=GOOGLE_GEMINI`

### Adding a new property
- **Same on every env?** → `application.properties`
- **Different per env?** → `application-{profile}.properties` for each env that has a value
- **Secret that shouldn't be in git?** → see §5 (CI variables)

### `.gitignore`
The wildcard that hid `application-*.properties` is gone. All profile files are tracked. If you need a personal local override, use a different filename like `application-local-bijal.properties` and `--spring.config.additional-location=` to load it.

---

## 4. How the active profile gets selected

Spring picks profiles from any of:
- Env var `SPRING_PROFILES_ACTIVE`
- JVM arg `-Dspring.profiles.active`
- Command-line `--spring.profiles.active`
- Property `spring.profiles.active` in `application.properties`

We **do not** use the property anymore (it would force-pick a profile per branch). Instead:

| Where | How it's set |
|---|---|
| **Devops deploy → SIT** | `.gitlab-ci.yml` deploy job: `docker run ... -e SPRING_PROFILES_ACTIVE=uat ...` |
| **Local dev** | Set in your shell, IDE Run Config, or `mvn` invocation: `export SPRING_PROFILES_ACTIVE=local` (or `-Dspring.profiles.active=local`) |
| **Future prod** | Add `-e SPRING_PROFILES_ACTIVE=prod` to whatever runs the prod container |

Without an explicit profile, Spring loads only `application.properties` — the app will boot but datasource, mail, AWS, and the AI toggle will all be unset (most things will fail). Always set the profile.

---

## 5. Secrets / API keys

AI provider keys (`ANTHROPIC_KEY`, `OPENAI_KEY`, `GEMINI_KEY`, `GROK_KEY`) live as **masked GitLab CI variables** at `Settings → CI/CD → Variables`. They are:
- Marked **Masked** (logs strip them)
- **Not** marked Protected — devops isn't a protected branch yet, and Protected variables only inject on protected refs
- Read by the deploy job and forwarded into the running container via `docker run -e VARNAME` (no `=value`, so the value is inherited from the runner's environment)

The application reads them through `System.getenv()` (e.g. `LlmStartupCheck.java`). Missing keys produce a startup WARN, not a crash.

To add another secret:
1. Add it to `Settings → CI/CD → Variables` (Masked, Hidden Visibility for sensitive ones)
2. Add `-e VARNAME` in the deploy job's `docker run` block in `.gitlab-ci.yml`
3. Read via `System.getenv("VARNAME")` in code

---

## 6. Manual seed jobs

Two seed jobs exist on the devops pipeline as **manual play buttons**:

| Job | What it loads | When to run |
|---|---|---|
| `seed-kia-uat-users` | 10 Kia test users + 2 sub-departments under Sales | After a fresh DB or full reset |
| `seed-kia-dealerships` | 1 country + 6 regions + 33 states + 394 cities + 627 dealerships + 1,154 locations | Same |

Both:
- Read DB credentials from `application-uat.properties` (single source of truth)
- Connect from the smartcomply-ssh runner to the UAT DB
- Are idempotent (`ON CONFLICT (id) DO NOTHING`) — safe to re-run
- Live as `scripts/seed-kia-*.sql` in the repo

---

## 7. Schema notes

JV's dealership-related entities (`Auditee`, `AuditeeLocation`, `AuditeeType`, `City`, `State`, `Country`, `ChecksheetAssignment`, `ChecksheetAuditeeType`) are **not** in any Flyway migration — they're created by Hibernate `ddl-auto=update` at first boot. This is a known gap; ideally they should be migrated to proper Flyway scripts.

The new `Region` entity follows the same pattern (auto-created), but the **V1.23** migration handles the bridging changes for existing UAT-style DBs (where the underlying tables already exist):
- Creates `regions` table outright (always safe — new table)
- Adds `states.region_id` + FK on existing `states`
- Adds `auditees.phone/email/website` on existing `auditees`
- Adds BI/lookup indexes (region grouping, FK joins)

`states.region_id NOT NULL` is enforced at the **JPA level** but cannot be enforced at the DB level on UAT until every row has a value. After the dealership seed populates them, a future V1.24 can `ALTER COLUMN region_id SET NOT NULL`.

---

## 8. Pointers

- Seed scripts: `scripts/seed-kia-uat-users.sql`, `scripts/seed-kia-dealerships.sql`
- Migrations: `src/main/resources/db/migration/V1.*.sql`
- CI: `.gitlab-ci.yml`
- Base config: `src/main/resources/application.properties`
- Local config: `src/main/resources/application-local.properties`
- UAT config: `src/main/resources/application-uat.properties`
- Pipelines UI: <https://gitlab.tiez.net/Tiez/smartcomply/-/pipelines>
- Variables UI: <https://gitlab.tiez.net/Tiez/smartcomply/-/settings/ci_cd> (Maintainer-only)
