# Just Talk — user sync (Keycloak → `app_users`)

A tiny scheduled job that mirrors every Keycloak user into our own **`app_users`** table, so we own
a queryable user directory for app-side things (usage, billing links, marketing) without depending
on Keycloak's internal schema.

- **Why a sync (not an app call):** Google is brokered through Keycloak, so Keycloak already has
  every user. This job pulls them server-side — it captures a user who signs in once and never
  reopens the app, and needs no cooperation from the clients.
- **Table:** `app_users`, keyed by the Keycloak `sub`. Self-migrates on first run (`schema.sql` is
  the reference copy). App-specific columns can be added later without touching Keycloak.
- **`paid`:** read in one call from the realm role members, so each row knows entitlement status.

## Deploy on Railway (same project as Keycloak)

1. New service in the `focused-growth` project → **Deploy from GitHub repo** → this repo, **Root
   Directory = `dictation/deployment/user-sync`** (Nixpacks builds the Node app).
2. **Variables:**

   | Variable | Value |
   |---|---|
   | `KC_URL` | `https://justtalk.trika.ai` (or the Railway Keycloak domain until DNS is live) |
   | `KC_REALM` | `justtalk` |
   | `KC_ADMIN` | `${{Keycloak.KEYCLOAK_ADMIN}}` |
   | `KC_ADMIN_PASSWORD` | `${{Keycloak.KEYCLOAK_ADMIN_PASSWORD}}` |
   | `DATABASE_URL` | `${{Postgres.DATABASE_URL}}` |

3. It's a **cron** (`railway.json` sets `*/10 * * * *`) — Railway runs it every 10 min, it upserts,
   then exits. Check the run logs: `user-sync: N users, M paid` / `upserted N rows`.

## Query the data

```sql
SELECT email, name, paid, keycloak_created_at FROM app_users ORDER BY first_seen_at DESC;
```

## Hardening (later)

- Swap the bootstrap admin for a **dedicated service-account client** in the `justtalk` realm with
  only the `view-users` role, and use `client_credentials` instead of the password grant.
- If you want **real-time** capture instead of a 10-min cron, move Keycloak onto our own image
  (`deployment/keycloak/Dockerfile`) and add an event-listener SPI (or a webhook provider) that
  upserts on the `LOGIN`/`REGISTER` events.
