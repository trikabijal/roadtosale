# Just Talk licensing — go-live checklist

Keycloak + Postgres are **deployed and running on Railway** (project `focused-growth`, both
services Online). This is the short list of things only you can do to finish wiring it up.

## Live facts

| | |
|---|---|
| Railway domain (now) | `https://keycloak-production-0756.up.railway.app` |
| Intended domain | `https://justtalk.trika.ai` (the app is hard-coded to this issuer) |
| Admin console | `<domain>/admin/` — user `admin`, password in **Railway → Keycloak → Variables → `KEYCLOAK_ADMIN_PASSWORD`** (reveal with the eye icon) |
| Google redirect URI (already set) | `https://justtalk.trika.ai/realms/justtalk/broker/google/endpoint` |

The app's OIDC issuer is `https://justtalk.trika.ai/realms/justtalk` (`EntitlementConfig.justTalk`),
so the custom domain must be live for sign-in to work. If you'd rather test on the Railway domain
first, change that one constant in `Shared/Sources/DictationCore/Entitlement.swift` and rebuild.

## Steps (≈10 min)

1. **Custom domain + hostname**
   - Railway → Keycloak service → **Settings → Networking → Custom Domain** → add `justtalk.trika.ai`.
     Railway shows a CNAME target.
   - At the `trika.ai` registrar, add **CNAME `justtalk` → that target**. Wait for it to verify.
   - Railway → Keycloak → **Variables** → set **`KC_HOSTNAME` = `https://justtalk.trika.ai`**
     (currently `${{RAILWAY_PUBLIC_DOMAIN}}`). This redeploys.

2. **Import the realm**
   - Admin console → realm dropdown → **Create realm** → **Browse** →
     `deployment/keycloak/justtalk-realm.json` → Create.
   - Confirms: realm `justtalk`, client `justtalk-app` (public + PKCE), realm role **`paid`**.

3. **Add Google as identity provider**
   - Realm `justtalk` → **Identity providers → Google** → paste the **Client ID + Client secret**
     from your Google OAuth client → Save.
   - Keycloak shows a Redirect URI — confirm it equals the one you gave Google (above).

4. **Grant yourself `paid` (test the unlock)**
   - Open the Mac app → menu → **Sign in** (or trigger dictation while locked) → sign in with Google.
   - Admin console → **Users** → your new user → **Role mapping → Assign role → `paid`**.
   - Back in the app: menu → **Account → Check again** (or just dictate) → it unlocks. Remove the
     role → it locks again (after the grace window / next check).

## User data — `app_users` table

Every Google sign-in already creates a Keycloak user row (email/name/roles) in Postgres. To get an
**app-owned, queryable** copy for other uses, deploy the sync job — see
`deployment/user-sync/README.md`. Add it as a second Railway service in this project (root dir
`dictation/deployment/user-sync`), wire the 5 env vars, and it upserts every user into `app_users`
on a 10-min cron. Query with:

```sql
SELECT email, name, paid, keycloak_created_at FROM app_users ORDER BY first_seen_at DESC;
```

## Later — payment automation

When payment lands (Stripe/RevenueCat), a webhook calls Keycloak's Admin REST API to grant `paid`
on success and remove it on cancellation. No app change — the gate already reads `paid`.
