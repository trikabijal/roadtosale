/**
 * E2E harness — boots the FULL live stack headlessly and tears it down.
 *
 * Black-box discipline: this harness does NOT import any BFF or Core source.
 * It launches a real Postgres (embedded-postgres, no Docker), the Core jar as a
 * child process, and the BFF as a child process, then exposes their base URLs
 * and a `pg` Pool for direct DB assertions.
 *
 *   embedded Postgres  ──>  Core (Java, :corePort)  <──  BFF (Node, :bffPort)
 *                                   ▲                          ▲
 *                                   └── SPRING_DATASOURCE_* ───┘ CORE_BASE_URL
 *
 * Everything transient (PG data dir, Core storage dir, child logs) lives under
 * qa/.tmp/<unique> and is removed on teardown.
 */

import { spawn, type ChildProcess } from "node:child_process";
import { createServer } from "node:net";
import { mkdtempSync, mkdirSync, rmSync, existsSync, readdirSync, openSync } from "node:fs";
import { execSync } from "node:child_process";
import { tmpdir } from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { request } from "undici";
import EmbeddedPostgres from "embedded-postgres";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
// qa/ -> road-to-sale-app/
const APP_ROOT = path.resolve(__dirname, "..", "..");
const BACKEND_DIR = path.join(APP_ROOT, "backend");
const BFF_DIR = path.join(APP_ROOT, "bff");
const QA_DIR = path.resolve(__dirname, "..");
const TMP_ROOT = path.join(QA_DIR, ".tmp");

const PG_USER = "rts";
const PG_PASS = "rts";
const PG_DB = "roadtosale";

export type Stack = {
  bffBase: string;
  coreBase: string;
  pg: {
    host: string;
    port: number;
    user: string;
    password: string;
    database: string;
  };
  teardown: () => Promise<void>;
};

/** Find a free TCP port by binding to :0 and reading the assigned port. */
function freePort(): Promise<number> {
  return new Promise((resolve, reject) => {
    const srv = createServer();
    srv.on("error", reject);
    srv.listen(0, "127.0.0.1", () => {
      const addr = srv.address();
      if (addr && typeof addr === "object") {
        const p = addr.port;
        srv.close(() => resolve(p));
      } else {
        srv.close(() => reject(new Error("could not get a free port")));
      }
    });
  });
}

async function waitForHealth(
  url: string,
  label: string,
  timeoutMs: number,
): Promise<void> {
  const start = Date.now();
  let lastErr: unknown;
  while (Date.now() - start < timeoutMs) {
    try {
      const res = await request(url, { method: "GET" });
      await res.body.text();
      if (res.statusCode >= 200 && res.statusCode < 300) return;
      lastErr = `status ${res.statusCode}`;
    } catch (e) {
      lastErr = e;
    }
    await new Promise((r) => setTimeout(r, 500));
  }
  throw new Error(
    `${label} did not become healthy at ${url} within ${timeoutMs}ms (last: ${String(
      lastErr,
    )})`,
  );
}

function findJar(): string {
  const target = path.join(BACKEND_DIR, "target");
  if (existsSync(target)) {
    const jars = readdirSync(target).filter(
      (f) => f.endsWith(".jar") && !f.endsWith("-sources.jar") && !f.includes("original"),
    );
    if (jars.length > 0) return path.join(target, jars[0]);
  }
  return "";
}

function buildJar(): void {
  // Only build if the jar is missing — building is slow and the orchestrator
  // may have pre-built it.
  // eslint-disable-next-line no-console
  console.log("[harness] Core jar missing — building with Maven (skip tests)...");
  execSync("mvn -q clean package -DskipTests", {
    cwd: BACKEND_DIR,
    stdio: "inherit",
  });
}

/**
 * Boot the whole stack. Throws (after best-effort cleanup) if anything fails to
 * come up. Always returns a `teardown` that is safe to call.
 */
export async function startStack(): Promise<Stack> {
  const tmpDir = mkdtempSync(path.join(TMP_ROOT_ensure(), "e2e-"));
  const pgDataDir = path.join(tmpDir, "pgdata");
  const storageDir = path.join(tmpDir, "photos");
  const logDir = tmpDir;

  let pg: EmbeddedPostgres | undefined;
  let core: ChildProcess | undefined;
  let bff: ChildProcess | undefined;
  let pgStarted = false;

  const teardown = async (): Promise<void> => {
    // BFF first, then Core, then Postgres.
    await killProc(bff, "BFF");
    await killProc(core, "Core");
    if (pg && pgStarted) {
      try {
        await pg.stop();
      } catch {
        /* ignore */
      }
    }
    try {
      rmSync(tmpDir, { recursive: true, force: true });
    } catch {
      /* ignore */
    }
  };

  try {
    // 1) Embedded Postgres on a free port.
    const pgPort = await freePort();
    pg = new EmbeddedPostgres({
      databaseDir: pgDataDir,
      user: PG_USER,
      password: PG_PASS,
      port: pgPort,
      persistent: false,
    });
    // eslint-disable-next-line no-console
    console.log(`[harness] initialising embedded Postgres on :${pgPort} ...`);
    await pg.initialise();
    await pg.start();
    pgStarted = true;
    await pg.createDatabase(PG_DB);
    // eslint-disable-next-line no-console
    console.log("[harness] embedded Postgres ready");

    // 2) Ensure the Core jar exists (build only if missing).
    let jar = findJar();
    if (!jar) {
      buildJar();
      jar = findJar();
      if (!jar) throw new Error("Core jar not found after build");
    }
    // eslint-disable-next-line no-console
    console.log(`[harness] using Core jar: ${path.basename(jar)}`);

    // 3) Spawn Core.
    const corePort = await freePort();
    const coreOut = openSync(path.join(logDir, "core.log"), "a");
    core = spawn(
      "java",
      ["-jar", jar],
      {
        cwd: BACKEND_DIR,
        env: {
          ...process.env,
          SPRING_PROFILES_ACTIVE: "dev",
          SERVER_PORT: String(corePort),
          SPRING_DATASOURCE_URL: `jdbc:postgresql://localhost:${pgPort}/${PG_DB}`,
          SPRING_DATASOURCE_USERNAME: PG_USER,
          SPRING_DATASOURCE_PASSWORD: PG_PASS,
          STORAGE_DIR: storageDir,
          JWT_SECRET: "e2e-test-only-secret-please-change-not-prod",
        },
        stdio: ["ignore", coreOut, coreOut],
      },
    );
    const coreBase = `http://localhost:${corePort}`;
    // eslint-disable-next-line no-console
    console.log(`[harness] starting Core on ${coreBase} (log: ${path.join(logDir, "core.log")}) ...`);
    await waitForHealth(`${coreBase}/health`, "Core", 90_000);
    // eslint-disable-next-line no-console
    console.log("[harness] Core healthy");

    // 4) Spawn BFF (tsx src/server.ts).
    const bffPort = await freePort();
    const bffOut = openSync(path.join(logDir, "bff.log"), "a");
    bff = spawn(
      "npx",
      ["tsx", "src/server.ts"],
      {
        cwd: BFF_DIR,
        env: {
          ...process.env,
          CORE_BASE_URL: coreBase,
          PORT: String(bffPort),
          HOST: "127.0.0.1",
        },
        stdio: ["ignore", bffOut, bffOut],
      },
    );
    const bffBase = `http://localhost:${bffPort}`;
    // eslint-disable-next-line no-console
    console.log(`[harness] starting BFF on ${bffBase} (log: ${path.join(logDir, "bff.log")}) ...`);
    await waitForHealth(`${bffBase}/health`, "BFF", 60_000);
    // eslint-disable-next-line no-console
    console.log("[harness] BFF healthy — stack is up");

    return {
      bffBase,
      coreBase,
      pg: {
        host: "localhost",
        port: pgPort,
        user: PG_USER,
        password: PG_PASS,
        database: PG_DB,
      },
      teardown,
    };
  } catch (err) {
    await teardown();
    throw err;
  }
}

function TMP_ROOT_ensure(): string {
  mkdirSync(TMP_ROOT, { recursive: true });
  return TMP_ROOT;
}

async function killProc(p: ChildProcess | undefined, label: string): Promise<void> {
  if (!p || p.exitCode !== null || p.signalCode !== null) return;
  await new Promise<void>((resolve) => {
    let done = false;
    const finish = () => {
      if (!done) {
        done = true;
        resolve();
      }
    };
    p.once("exit", finish);
    try {
      p.kill("SIGTERM");
    } catch {
      finish();
      return;
    }
    // Escalate to SIGKILL if it doesn't exit promptly.
    setTimeout(() => {
      try {
        if (p.exitCode === null && p.signalCode === null) p.kill("SIGKILL");
      } catch {
        /* ignore */
      }
      finish();
    }, 5_000);
  }).then(() => {
    void label;
  });
}

// Also export tmp dir cleanup helper for completeness (unused externally).
export { TMP_ROOT };
