# Base tenant — no data migrations

The `_base` tenant is the unbranded AuditPro identity. It ships no
tenant-specific data: no Kia users, no Kia dealerships, no Kia geography.
Universal schema migrations under `db/migration/` are sufficient.

This README exists only so the directory ships inside the WAR — Flyway
needs the classpath location to resolve at runtime when `tenant.id=_base`.

If a future "default" tenant ever needs data migrations (e.g. demo
sample dealerships for sales evaluation), drop `V100.xxx__*.sql` files
in here and they'll auto-apply for any deployment without an explicit
TENANT_ID env var.
