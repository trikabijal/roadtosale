# AuditPro — Build & CI/CD Guide

## Prerequisites

- Java 17
- Maven 3.9+ (or use `./mvnw` wrapper)
- PostgreSQL 15 (for running locally)
## LLM Dependency

The Trika LLM JAR (`com.trika:llm`) is checked into the repo at `lib/llm-{version}.jar`. No external registry, no tokens, no setup needed. Maven picks it up automatically via `systemPath` in `pom.xml`.

---

## Build Commands

```bash
# Compile
./mvnw compile

# Run tests
./mvnw test -Dtest='!com.checkSheet.demo.ApplicationTests'

# Package WAR
./mvnw package -DskipTests

# Run locally
./mvnw spring-boot:run -Dspring-boot.run.profiles=local
```

---

## CI/CD Pipeline

Defined in `.gitlab-ci.yml`. Four stages:

| Stage | Trigger | What it does |
|-------|---------|--------------|
| **compile** | PR, push to development/main | `mvn compile` — checks code compiles with LLM JAR |
| **test** | PR, push to development/main | `mvn test` — runs 23 automated tests |
| **package** | Push to development/main, or manual | `mvn package` — builds WAR artifact |
| **docker** | Manual only | Builds Docker image, pushes to GitLab registry |

### Pipeline flow
```
PR opened → compile → test → (pass/fail feedback on PR)
Merged to development → compile → test → package (auto) → docker (manual)
Merged to main → compile → test → package (auto) → docker (manual)
```

Releases and deploys are **manual only** (`when: manual`). No accidental deployments.

---

## Docker

```bash
# Build locally
./mvnw package -DskipTests
docker build -t auditpro:latest .

# Run
docker run -p 8080:8089 \
  -e ANTHROPIC_KEY=your_key \
  -e OPENAI_KEY=your_key \
  -e GEMINI_KEY=your_key \
  -e DB_HOST=host.docker.internal \
  -e DB_PORT=5432 \
  -e DB_NAME=smartcomply \
  -e DB_USERNAME=your_user \
  -e DB_PASSWORD=your_pass \
  auditpro:latest
```

---

## Startup Checks

On startup, AuditPro checks:

1. **LLM JAR on classpath** — logs ERROR with pom.xml fix instructions if missing
2. **API keys configured** — logs WARN listing which providers are/aren't configured
3. **Database connectivity** — standard Spring Boot datasource check

---

## Test Artifacts

CI produces JUnit XML reports at `target/surefire-reports/TEST-*.xml`. GitLab renders these as test results on the merge request.

---

## Updating the LLM Module

When a new version of `com.trika:llm` is built:

1. Build the JAR from the LLM repo (`trikabijal/llm`): `mvn package`
2. Copy the new JAR to `lib/`:
   ```bash
   cp ../llm/target/llm-1.1.0.jar lib/
   rm lib/llm-1.0.0.jar  # remove old version
   ```
3. Update `pom.xml` — change version and systemPath:
   ```xml
   <version>1.1.0</version>
   <systemPath>${project.basedir}/lib/llm-1.1.0.jar</systemPath>
   ```
4. Run tests: `./mvnw test`
5. Commit the new JAR and updated pom.xml
