# Just Talk — Keycloak licensing / identity server (Railway)

Keycloak is the single identity authority for Just Talk. It brokers **Google sign-in**, stores
every registered user, and carries the **`paid`** entitlement in the token the apps gate on.

- The macOS + iOS apps talk **only** to Keycloak (generic OIDC + PKCE via AppAuth). They never
  see Google directly.
- Entitlement = the realm role **`paid`**. New users don't have it → app is locked. You grant it
  in the admin console now; a payment webhook grants it via the Admin REST API later.
- Nothing secret is committed. The Google client secret lives only in Keycloak's admin console;
  DB creds and the bootstrap admin password live only in Railway env vars.

---

## The only thing you hand back to me

**The Railway public domain** (e.g. `https://justtalk-auth.up.railway.app`).

That's it. From the domain I derive the app's OIDC config (issuer
`https://<domain>/realms/justtalk`, client `justtalk-app`). **The Google client ID/secret never
reach me or the repo** — you paste them straight into Keycloak's admin console.

---

## Step 1 — Deploy Keycloak on Railway

1. **New Project → Deploy from GitHub repo** → this repo. In the service's **Settings**, set
   **Root Directory = `deployment/keycloak`** so Railway builds this Dockerfile.
2. **Add a database:** New → Database → **PostgreSQL**.
3. **Keycloak service → Variables** — set these (the `${{Postgres.*}}` are Railway reference
   variables that pull from the Postgres service automatically):

   | Variable | Value |
   |---|---|
   | `KC_DB_URL` | `jdbc:postgresql://${{Postgres.PGHOST}}:${{Postgres.PGPORT}}/${{Postgres.PGDATABASE}}` |
   | `KC_DB_USERNAME` | `${{Postgres.PGUSER}}` |
   | `KC_DB_PASSWORD` | `${{Postgres.PGPASSWORD}}` |
   | `KC_HTTP_ENABLED` | `true` |
   | `KC_PROXY_HEADERS` | `xforwarded` |
   | `KC_HTTP_PORT` | `8080` |
   | `KC_BOOTSTRAP_ADMIN_USERNAME` | `admin` |
   | `KC_BOOTSTRAP_ADMIN_PASSWORD` | *(a strong password — you'll change it after first login)* |
   | `KC_HOSTNAME` | *(set in step 4, after the domain exists)* |

4. **Settings → Networking → Generate Domain.** Set the **target port to `8080`.** Copy the
   `https://<name>.up.railway.app` URL, set `KC_HOSTNAME` to it, and redeploy.
5. Open `https://<domain>/` → **Administration Console** → log in with the bootstrap admin.
   Confirm the **`justtalk`** realm is present (imported on first boot) with a realm role
   **`paid`** and a client **`justtalk-app`**.

## Step 2 — Create the Google OAuth client

The redirect URI is deterministic from your domain, so do Google before touching Keycloak's IdP
screen:

1. Google Cloud Console → **New project** "Just Talk".
2. **OAuth consent screen** → External. App name + your support email. Scopes: `openid`,
   `email`, `profile`. Add your email under **Test users** (up to 100, no Google review needed).
3. **Credentials → Create OAuth client ID → Web application.**
   Authorized redirect URI (exact):
   ```
   https://<domain>/realms/justtalk/broker/google/endpoint
   ```
4. Copy the **Client ID** and **Client secret**.

## Step 3 — Wire Google into Keycloak

1. Admin console → realm **justtalk** → **Identity providers → Google**.
2. Paste the **Client ID** and **Client secret** → **Save**. (Keycloak shows the same Redirect
   URI as above — a good cross-check against what you pasted into Google.)

## Step 4 — Grant yourself `paid` (test the gate)

1. Sign in once through the app (creates your user via Google).
2. Admin console → **Users** → your user → **Role mapping → Assign role → `paid`**.
3. The app unlocks on its next entitlement check. Remove the role → it locks again.

---

## Later: payment automation

When payment lands (Stripe / RevenueCat), a webhook calls Keycloak's **Admin REST API** to grant
the `paid` role on successful payment and remove it on cancellation. No app change — the gate
already reads `paid`. That wiring is out of scope for this bring-up.

## Notes

- Keycloak **26.3**, optimized image (Postgres vendor + health baked at build). Health check:
  `/health/ready`.
- Realm import is idempotent — an existing `justtalk` realm is left untouched on redeploy. To
  re-import after editing `justtalk-realm.json`, delete the realm in the console first.
- `KC_BOOTSTRAP_ADMIN_*` create a **temporary** admin. After first login, create a real admin
  account and remove/rotate the bootstrap one.
