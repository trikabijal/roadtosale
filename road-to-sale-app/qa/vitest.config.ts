import { defineConfig } from "vitest/config";

/**
 * The E2E suite boots a real stack once (beforeAll) and runs an ORDERED
 * lifecycle. Run a single fork, no parallelism, with generous timeouts to cover
 * JVM boot + embedded Postgres init.
 */
export default defineConfig({
  test: {
    include: ["tests/**/*.test.ts"],
    pool: "forks",
    poolOptions: { forks: { singleFork: true } },
    fileParallelism: false,
    sequence: { concurrent: false },
    testTimeout: 30_000,
    hookTimeout: 200_000,
    teardownTimeout: 60_000,
  },
});
