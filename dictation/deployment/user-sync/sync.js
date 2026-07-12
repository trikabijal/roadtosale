// Just Talk user sync — mirrors Keycloak's user directory into our own `app_users` table.
//
// Runs on a schedule (Railway cron), reads the Keycloak Admin API, and upserts every user +
// their `paid` status into Postgres. Server-side and app-independent: a user who signs in once
// and never reopens the app is still captured. Idempotent — safe to run as often as you like.
//
// Env:
//   KC_URL              Keycloak base URL, e.g. https://justtalk.trika.ai
//   KC_REALM            realm to sync (default: justtalk)
//   KC_ADMIN            bootstrap admin username (Keycloak service var KEYCLOAK_ADMIN)
//   KC_ADMIN_PASSWORD   bootstrap admin password (Keycloak service var KEYCLOAK_ADMIN_PASSWORD)
//   DATABASE_URL        Postgres connection string
//   PAID_ROLE           realm role that marks a paid user (default: paid)

import pg from 'pg';

const {
  KC_URL,
  KC_REALM = 'justtalk',
  KC_ADMIN,
  KC_ADMIN_PASSWORD,
  DATABASE_URL,
  PAID_ROLE = 'paid',
} = process.env;

function requireEnv() {
  const missing = ['KC_URL', 'KC_ADMIN', 'KC_ADMIN_PASSWORD', 'DATABASE_URL']
    .filter((k) => !process.env[k]);
  if (missing.length) {
    console.error(`user-sync: missing env: ${missing.join(', ')}`);
    process.exit(1);
  }
}

/** Get an admin access token via the password grant against the master realm (admin-cli client). */
async function adminToken() {
  const res = await fetch(`${KC_URL}/realms/master/protocol/openid-connect/token`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({
      grant_type: 'password',
      client_id: 'admin-cli',
      username: KC_ADMIN,
      password: KC_ADMIN_PASSWORD,
    }),
  });
  if (!res.ok) throw new Error(`token failed: ${res.status} ${await res.text()}`);
  return (await res.json()).access_token;
}

async function kcGet(token, path) {
  const res = await fetch(`${KC_URL}/admin/realms/${KC_REALM}${path}`, {
    headers: { Authorization: `Bearer ${token}` },
  });
  if (res.status === 404) return null; // realm/role not created yet — treat as empty
  if (!res.ok) throw new Error(`GET ${path} failed: ${res.status} ${await res.text()}`);
  return res.json();
}

/** All users in the realm, paginated. */
async function allUsers(token) {
  const out = [];
  const pageSize = 100;
  for (let first = 0; ; first += pageSize) {
    const page = await kcGet(token, `/users?first=${first}&max=${pageSize}&briefRepresentation=false`);
    if (!page || page.length === 0) break;
    out.push(...page);
    if (page.length < pageSize) break;
  }
  return out;
}

/** Set of user ids that hold the paid realm role (one call, not N). */
async function paidUserIds(token) {
  const members = await kcGet(token, `/roles/${encodeURIComponent(PAID_ROLE)}/users?max=2000`);
  return new Set((members || []).map((u) => u.id));
}

async function main() {
  requireEnv();
  const token = await adminToken();
  const users = await allUsers(token);
  const paid = await paidUserIds(token);
  console.log(`user-sync: ${users.length} users, ${paid.size} paid`);

  const pool = new pg.Pool({
    connectionString: DATABASE_URL,
    ssl: DATABASE_URL.includes('localhost') ? false : { rejectUnauthorized: false },
  });
  const client = await pool.connect();
  try {
    // Self-migrate: create the table on first run (mirrors schema.sql). Idempotent.
    await client.query(`
      CREATE TABLE IF NOT EXISTS app_users (
        keycloak_sub        text PRIMARY KEY,
        email               text,
        name                text,
        enabled             boolean,
        paid                boolean      NOT NULL DEFAULT false,
        keycloak_created_at timestamptz,
        first_seen_at       timestamptz  NOT NULL DEFAULT now(),
        last_synced_at      timestamptz  NOT NULL DEFAULT now()
      );
      CREATE INDEX IF NOT EXISTS app_users_email_idx ON app_users (email);
      CREATE INDEX IF NOT EXISTS app_users_paid_idx  ON app_users (paid);
    `);

    let upserts = 0;
    for (const u of users) {
      const createdAt = u.createdTimestamp ? new Date(u.createdTimestamp) : null;
      await client.query(
        `INSERT INTO app_users
           (keycloak_sub, email, name, enabled, paid, keycloak_created_at, last_synced_at)
         VALUES ($1, $2, $3, $4, $5, $6, now())
         ON CONFLICT (keycloak_sub) DO UPDATE SET
           email = EXCLUDED.email,
           name = EXCLUDED.name,
           enabled = EXCLUDED.enabled,
           paid = EXCLUDED.paid,
           keycloak_created_at = EXCLUDED.keycloak_created_at,
           last_synced_at = now()`,
        [
          u.id,
          u.email ?? null,
          [u.firstName, u.lastName].filter(Boolean).join(' ') || u.username || null,
          u.enabled ?? true,
          paid.has(u.id),
          createdAt,
        ],
      );
      upserts++;
    }
    console.log(`user-sync: upserted ${upserts} rows`);
  } finally {
    client.release();
    await pool.end();
  }
}

main().catch((e) => {
  console.error('user-sync failed:', e);
  process.exit(1);
});
