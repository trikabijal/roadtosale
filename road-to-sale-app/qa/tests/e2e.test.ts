import { describe, it } from "vitest";

/**
 * Road to Sale — full-stack headless E2E (Phase 3 implements this).
 *
 * Black-box discipline: this harness talks ONLY to the running BFF over HTTP
 * (and may read Postgres directly to assert persistence). It never imports BFF
 * or Core source. See README.md for the intended scenario.
 */
describe("Road to Sale E2E (lifecycle)", () => {
  it.skip("login -> create session -> POST events -> upload photo -> submit -> read back derived outcomes", () => {
    // Phase 3: boot Core (zonky embedded Postgres) + BFF, then exercise the
    // full lifecycle through the BFF facade and assert DB rows, tenant
    // isolation (dealership A gets 404 for B's session), and event idempotency.
  });
});
