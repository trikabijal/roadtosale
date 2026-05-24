# AGENTS.md — CheckSheet Backend

> This document is the authoritative reference for AI agents (Copilot, Claude, Cursor, etc.) and human contributors working in the **CheckSheet** Spring Boot codebase. Read it before making any changes.

---

## Table of Contents

1. [Project Overview](#1-project-overview)
2. [Tech Stack & Key Dependencies](#2-tech-stack--key-dependencies)
3. [Repository Layout](#3-repository-layout)
4. [Architecture & Layer Conventions](#4-architecture--layer-conventions)
5. [Domain Model](#5-domain-model)
6. [Enumerations (Constants)](#6-enumerations-constants)
7. [Security Model](#7-security-model)
8. [API Surface](#8-api-surface)
9. [Async & Background Processing](#9-async--background-processing)
10. [File & Storage Handling](#10-file--storage-handling)
    11. [Notification & Email](#11-notification--email)
12. [Error Handling Conventions](#12-error-handling-conventions)
13. [Coding Conventions & Rules for Agents](#13-coding-conventions--rules-for-agents)
14. [Running & Building Locally](#14-running--building-locally)
15. [Docker](#15-docker)
16. [Environment Variables / application.properties Keys](#16-environment-variables--applicationproperties-keys)

---

## 1. Project Overview

**CheckSheet** is a quality-management platform used in manufacturing/production environments. It enables organisations to:

- **Author** structured checksheets (templates with headers, questions, result types, and general fields).
- **Execute** those checksheets at defined frequencies (Shift / Daily / Weekly / Monthly / Unplanned).
- **Validate and approve** both the checksheet template itself *and* the data captured in each filled instance.
- **Analyse** compliance and trends via a dashboard that surfaces Plan-vs-Actual reports, compliance heatmaps, trend charts, and completion funnels.
- **Administer** users, departments, roles, and fine-grained permissions.

The backend is a standalone Spring Boot REST API. It is deployed as a Docker container and speaks to a relational database (PostgreSQL implied by the native SQL snippets).

---

## 2. Tech Stack & Key Dependencies

| Concern | Technology |
|---|---|
| Framework | Spring Boot 3.x (Jakarta EE namespaces) |
| Build | Maven Wrapper (`mvnw`) |
| Security | Spring Security + stateless JWT (`JwtService`, `JwtAuthenticationFilter`) |
| ORM | Spring Data JPA / Hibernate |
| Database | PostgreSQL (native SQL present; JPA entities use Hibernate DDL) |
| File Storage | AWS S3 (`AmazonS3` SDK v1) + local file storage fallback |
| Push Notifications | Firebase Cloud Messaging (FCM) via `FirebaseInitializer` |
| Email | Spring Mail (`EmailService`) with async thread pool |
| Authentication Extension | LDAP (`LdapAuth`, `Constants`) |
| Excel Export | Apache POI via `ExcelSheet` service |
| Utilities | Lombok, MapStruct (implied by DTO patterns) |
| API Docs | SpringDoc OpenAPI / Swagger UI |
| Containerisation | Docker (`Dockerfile` at repo root) |

---

## 3. Repository Layout

```
.
├── Dockerfile
├── pom.xml
├── mvnw / mvnw.cmd
└── src/main/java/com/checkSheet/
    ├── Application.java                  # Entry point; thread pool beans
    ├── annotation/
    │   └── RoleAuthorization.java        # Custom meta-annotation for role guards
    ├── config/                           # Spring config beans
    ├── constant/                         # Enums used across the domain
    ├── controller/                       # REST controllers (@RequestMapping("/api/..."))
    ├── DAO/                              # Low-level EntityManager-based query objects
    ├── DTO/                              # Data Transfer Objects (request/response shapes)
    │   ├── request/                      # Specialised request DTOs
    │   └── response/                     # Generic response wrappers
    ├── entity/                           # JPA entities (database table mappings)
    ├── exception/                        # CustomException + global handler
    ├── helper/                           # Stateless utility classes
    ├── LDAP/                             # LDAP auth helpers
    ├── repository/                       # Spring Data JPA repositories
    └── service/                          # Business logic
        ├── Email/
        ├── export/xlsx/
        └── Notification/
```

---

## 4. Architecture & Layer Conventions

The codebase follows a strict **Controller → Service → Repository / DAO** layering.

### 4.1 Controllers
- Annotated with `@RestController` and `@CrossOrigin(origins = "*", allowedHeaders = "*")`.
- Base path is always `/api/<resource>` (e.g., `/api/checksheet`, `/api/user`).
- Responsibilities: deserialise request body/path params, call **one** service method, wrap in `ResponseEntity`. No business logic lives here.
- All exceptions are caught and converted to a `ResponseDTO<>(false, message)` with the appropriate HTTP status.

### 4.2 Services
- Defined as an interface + `Impl` class (e.g., `ChecksheetService` / `ChecksheetServiceImpl`).
- Business logic, permission checks (`permissionService.hasPermission(...)`), and data-assembly logic belong here.
- Services call Spring Data JPA repositories for simple CRUD and `DAO` classes for complex queries.
- Async methods are annotated `@Async("threadPoolTaskExecutorForNotification")` or `@Async("threadPoolTaskExecutorForEmail")`.

### 4.3 Repositories
- Standard Spring Data JPA interfaces (`extends JpaRepository<Entity, Long>`).
- Custom JPQL/native queries are expressed with `@Query`.

### 4.4 DAOs
- Use `EntityManager` directly for complex, dynamic queries (e.g., dashboard aggregations, filtered lists).
- Annotated `@Repository` and `@Transactional` where writes occur.

### 4.5 DTOs vs Entities
- **Entities** map 1-to-1 with database tables. Never expose them directly from controllers.
- **DTOs** are POJO classes used for both request deserialization and response serialization. They live under `com.checkSheet.DTO`.
- The generic response wrapper is `ResponseDTO<T>(boolean success, T data)` / `ResponseDTO<T>(boolean success, String message)`.
- Pagination is wrapped in `PagedResponse<T>`.

---

## 5. Domain Model

### Core Entities

| Entity | Table / Purpose |
|---|---|
| `Checksheet` | Master template. Has a status lifecycle (see §6). Belongs to a `Department`. |
| `ChksHeader` | Section header within a checksheet template. |
| `ChksHeaderData` | A row/column definition within a header. |
| `ChksQuestion` | An individual question within a header section. |
| `ChksQuestionResult` | The expected result configuration for a question (Objective / Subjective). |
| `ChksQuestionResultOption` | Option values for multi-choice questions. |
| `ChksQuestionResultMatrix` | Matrix-style result definition. |
| `ChksQuestionFile` | File attachment on a question definition. |
| `ChksGeneralField` | A named general field on a checksheet (e.g., machine ID, batch number). |
| `ChksGeneralFieldValue` | Filled value for a general field on a user checksheet instance. |
| `UserChecksheet` | A **filled instance** of a checksheet by an operator. |
| `UserChecksheetAnswer` | A single answer to a question within a user checksheet. |
| `UserChecksheetMatrixAnswers` | Answers for matrix-type questions. |
| `UserChecksheetTraceValue` | Trace/tracking values associated with an answer. |
| `UsrChksheetAnsJudgement` | Pass/fail judgement on an answer. |
| `UsrChksheetAnsJudgementFile` | File evidence attached to a judgement. |
| `UserChecksheetValidation` | Template-level validation record (validator signs off). |
| `UserChecksheetApproval` | Template-level approval record (approver signs off). |
| `ChecksheetValidation` | Template design validation record. |
| `ChecksheetApproval` | Template design approval record. |
| `Department` | Organisational unit. Can have parent (master) departments. |
| `NpdMaster` | NPD (New Product Development) master reference. |
| `User` | Application user. |
| `Role` | Named role assigned to users per department (`UserRoleDepartment`). |
| `Permission` | Granular permission strings (e.g., `CHECKSHEET_FILL_ANSWER`). |
| `RolePermission` | Many-to-many join between `Role` and `Permission`. |
| `UserRoleDepartment` | Assigns a `User` to a `Role` within a `Department`. |
| `LovData` | List-of-Values lookup data. |
| `SurpriseChecksheet` | Surprise (unplanned/audit) checksheet variant. |
| `APIHistory` | Audit log of API calls. |
| `AppVersion` | Mobile app version metadata. |
| `RefreshToken` | JWT refresh token storage. |

### Key Relationships
- A `Checksheet` has many `ChksHeader` → many `ChksHeaderData` → many `ChksQuestion` → one `ChksQuestionResult`.
- A `UserChecksheet` is an instance of a `Checksheet` for a specific date/shift/user.
- `UserChecksheetAnswer` links a `UserChecksheet` to a `ChksQuestion` with the actual value.
- `UserRoleDepartment` is the three-way join that drives all RBAC decisions at runtime.

---

## 6. Enumerations (Constants)

All enums live in `com.checkSheet.constant` and follow the same pattern: a `text` (human-readable label) and `value` (enum name string), plus a static `getEnumByString` factory.

| Enum | Values |
|---|---|
| `ChecksheetStatusType` | `NEW`, `CREATE_TEMPLATE`, `CREATE_CONTENT`, `SUBMITTED_FOR_VALIDATE`, `INVALIDATED`, `VALIDATED`, `APPROVED`, `NOT_APPROVED` |
| `ChecksheetApprovalStatusType` | `APPROVED`, `NOT_APPROVED` |
| `ChecksheetDataApprovalStatusType` | `APPROVED`, `NOT_APPROVED` |
| `ChecksheetValidationStatusType` | *(Validated/Invalidated variants)* |
| `ChecksheetDataValidationStatusType` | `VALIDATED`, `INVALIDATED` |
| `ChecksheetFrequencyType` | `SHIFT`, `DAILY`, `WEEKLY`, `MONTHLY`, `UNPLANNED` |
| `ChecksheetType` | `PUBLIC`, `PRIVATE` |
| `UserChecksheetStatusType` | *(draft/submitted/etc.)* |
| `ChksQuestionResultType` | *(result type discriminator)* |
| `ChksQuestionResultObjectiveType` | *(objective measurement types)* |
| `SystemRole` | System-level role constants |
| `EmailTemplate` | Named email template identifiers |

---

## 7. Security Model

### JWT Authentication
- `JwtService` signs and validates tokens.
- `JwtAuthenticationFilter` intercepts every request, extracts the `Authorization: Bearer <token>` header, validates it, and populates `SecurityContextHolder`.
- `CustomUserDetails` wraps the `User` entity for Spring Security.
- `CustomAuthenticationEntryPoint` returns a structured JSON 401 for unauthenticated requests.

### Whitelist (no auth required)
```
/swagger-ui/**
/v3/api-docs/**
/api/user/login
/api/user/register
/api/user/hello
/api/user/refreshToken
/api/checksheet/doc/**
```

### Session Policy
Stateless — no `HttpSession` is created (`SessionCreationPolicy.STATELESS`).

### Role-Based Access & Permissions
- Roles are defined in `SystemRole` and assigned per-department via `UserRoleDepartment`.
- Fine-grained permission checks use `permissionService.hasPermission(userId, "PERMISSION_STRING")`.
- The `@RoleAuthorization` annotation (custom meta-annotation) can guard controller methods declaratively.
- Menu access per role is computed in `UserServiceImpl` (see `getSystemAdminMenus()`, `getDepartmentAdminMenus()`, etc.).

### Key Permission Strings
- `CHECKSHEET_FILL_ANSWER` — operators who fill out checksheets
- `USER_PASSWORD_RESET` — admins who can reset other users' passwords
- *(add others here as they are introduced)*

### LDAP
`LdapAuth` provides an alternative authentication path via an LDAP directory. Configuration constants are in `com.checkSheet.LDAP.Constants`.

### Logout
`LogoutService` invalidates the `RefreshToken` on logout.

---

## 8. API Surface

All controllers are under `/api/`. Every endpoint returns `ResponseDTO<T>` on success or `ResponseDTO<>(false, errorMessage)` on failure.

| Controller | Base Path | Key Operations |
|---|---|---|
| `UserController` | `/api/user` | login, register, get users, update user, reset/update password, get menus |
| `ChecksheetController` | `/api/checksheet` | CRUD checksheets, versioning, status updates, file download/URL, LOV data |
| `ChecksheetApprovalController` | `/api/checksheetApproval` | Create / get template approval |
| `ChecksheetValidationController` | `/api/checksheetValidation` | Create / get template validation |
| `ChksHeaderController` | `/api/chksHeader` | CRUD section headers |
| `ChksHeaderDataController` | `/api/chksHeaderData` | CRUD header data rows |
| `ChksQuestionController` | `/api/chksQuestion` | CRUD questions, bulk operations |
| `ChksQuestionResultController` | `/api/chksQuestionResult` | CRUD result configurations |
| `ChksGeneralFieldController` | `/api/chksGeneralField` | CRUD general fields |
| `UserChecksheetController` | `/api/userChecksheet` | Create/fill instances, submit answers (plain, matrix, trace, judgement), download XLSX |
| `UserChecksheetApprovalController` | `/api/userChecksheetApproval` | Approve filled checksheet data |
| `UserChecksheetValidationController` | `/api/userChecksheetValidation` | Validate filled checksheet data |
| `DashboardController` | `/api/dashboard` | Plan-vs-actual, compliance heatmap, trend charts, completion funnel |
| `DepartmentController` | `/api/department` | CRUD departments |
| `MasterDepartmentController` | `/api/masterDepartment` | CRUD master (parent) departments |
| `RoleController` | `/api/role` | CRUD roles |
| `PermissionController` | `/api/permission` | CRUD permissions |
| `NpdMasterController` | `/api/npdMaster` | CRUD NPD master records |
| `SurprizeChecksheetController` | `/api/surprizeChecksheet` | Surprise/unplanned checksheet operations |
| `APIHistoryContoller` | `/api/apiHistory` | Retrieve audit log of API calls |

### Response Shape

```json
// Success
{ "success": true, "data": { ... } }

// Error
{ "success": false, "message": "Human-readable error" }

// Paginated
{ "content": [...], "page": 0, "size": 20, "totalElements": 100, "totalPages": 5 }
```

---

## 9. Async & Background Processing

Three dedicated thread pools are defined in `Application.java`:

| Bean Name | Prefix | Core / Max Pool | Use |
|---|---|---|---|
| `threadPoolTaskExecutorForNotification` | `Async-` | 5 / 20 | FCM push notifications |
| `threadPoolTaskExecutorForEmail` | `Async-Email` | 5 / 20 | Plain emails |
| `threadPoolTaskExecutorForEmailWithAttachment` | `Async-Email` | 5 / 20 | Emails with attachments |

- `@EnableAsync` and `@EnableScheduling` are active on the main class.
- Async service methods must declare `@Async("threadPoolTaskExecutorFor...")` with the correct bean name.
- `@EnableTransactionManagement` is active; use `@Transactional` on any method that writes to multiple tables.

---

## 10. File & Storage Handling

### AWS S3
- `AWSS3Config` builds an `AmazonS3` bean using credentials from `application.properties`.
- `AWSS3Service` / `AWSS3ServiceImpl` wrap upload, download, and pre-signed URL generation.
- Checksheet-level file operations go through `ChecksheetController.downloadFile`, `.getS3FileURL`, `.getS3FilesURL`.

### Local File Storage
- `FileStorageProperties` holds the local upload directory path.
- `FileStorageUtil.getDocs(directoryPath, fileName, request)` serves files from local disk.
- Endpoint: `GET /api/checksheet/doc/{directoryPath}/{fileName}` (whitelisted — no auth).

### File-Backed Entities
- `ChksQuestionFile` — file attached to a question definition.
- `ChksHeaderDataFile` — file attached to a header data row.
- `UsrChksheetAnsJudgementFile` — file evidence for a judgement.

### Excel Export
- `ExcelSheet` (under `service/export/xlsx/`) generates `.xlsx` files via Apache POI.
- `UserChecksheetController.downloadUserChecksheetWithAnswers` streams XLSX directly to the HTTP response with `Content-Disposition: attachment; filename=UserChecksheet.xlsx`.

---

## 11. Notification & Email

### Push Notifications (FCM)
- `FirebaseInitializer` bootstraps the Firebase SDK on startup.
- `FCMInitializerService` / `FCMInitializerServiceImpl` send push notifications asynchronously via the `threadPoolTaskExecutorForNotification` pool.

### Email
- `EmailService` sends emails; async variants use `threadPoolTaskExecutorForEmail` / `threadPoolTaskExecutorForEmailWithAttachment`.
- Template identifiers are in the `EmailTemplate` enum.

---

## 12. Error Handling Conventions

- All service methods declare `throws CustomException`.
- `CustomException(String message, HttpStatus status)` is the single exception type used throughout.
- Controllers catch `CustomException` and return `ResponseEntity.status(e.getHttpStatus()).body(new ResponseDTO<>(false, e.getMessage()))`.
- Unexpected exceptions (`Exception e`) are caught, `e.printStackTrace()` is called, and they are re-wrapped as `CustomException(..., INTERNAL_SERVER_ERROR)`.
- **Agents must follow this pattern** — do not introduce new exception types or change the wrapping strategy without updating all callers.

---

## 13. Coding Conventions & Rules for Agents

### General
- Java 17+ (Jakarta namespace, not `javax`).
- Lombok is in use — prefer `@Getter`, `@Setter`, `@Data`, `@Builder`, `@NoArgsConstructor`, `@AllArgsConstructor` over manually written boilerplate.
- Every new feature requires: **entity** (if new table) → **repository** → **DAO** (if complex query) → **DTO** → **service interface + impl** → **controller**.
- Never add business logic to controllers or entities.
- Never expose JPA entities directly from controller responses; always map to a DTO.

### Naming
- Entities: `PascalCase`, noun (e.g., `ChksQuestion`).
- DTOs: `EntityNameDTO` (e.g., `ChksQuestionDTO`).
- Repositories: `EntityNameRepository`.
- Services: `EntityNameService` (interface) + `EntityNameServiceImpl`.
- DAOs: `EntityNameDAO`.
- Controllers: `EntityNameController`.
- API paths: `camelCase` (e.g., `/api/chksQuestion/createQuestion`).

### Permission Checks
- All mutating operations must call `permissionService.hasPermission(currentUser.get().getId(), "PERMISSION_KEY")` before executing.
- Read operations on sensitive data should also be guarded where appropriate.
- Retrieve the current user with `utilityService.getCurrentLoggedInUser()` and check `Optional.isPresent()` before use.

### Enums
- When adding a new status or type value, add it to the appropriate enum in `com.checkSheet.constant`.
- Follow the existing pattern: provide `text` (label), override `toString()` to return `value`, and add a `getEnumByString` factory.

### Database / JPA
- Use `@Transactional` on any service method that performs more than one write.
- Complex filtered/aggregated queries belong in a `DAO` class using `EntityManager`, not in `@Query` annotations unless they are simple.
- Use `Optional<Entity>` return types from repositories and always call `.isPresent()` before `.get()`.

### API History
- Significant API calls are logged via `APIHistoryService`. Continue using it for new write endpoints.

### CORS
- `@CrossOrigin(origins = "*", allowedHeaders = "*")` is present on every controller. Do not remove it.
- The `WebConfig.java` CORS configuration is currently commented out — do not uncomment without team review.

### Tests
- No test files were detected in the packed codebase. Before adding new features, add a corresponding unit test in `src/test/java/com/checkSheet/`.

---

## 14. Running & Building Locally

```bash
# Build (skip tests for speed)
./mvnw clean package -DskipTests

# Run
./mvnw spring-boot:run

# Or run the JAR directly
java -jar target/checkSheet-*.jar
```

Required: Java 17+, Maven (or use `mvnw`), a running PostgreSQL instance, and all env vars from §16 populated in `src/main/resources/application.properties`.

---

## 15. Docker

```bash
# Build image
docker build -t checksheet-backend .

# Run (pass env vars as needed)
docker run -p 8080:8080 \
  -e SPRING_DATASOURCE_URL=jdbc:postgresql://db:5432/checksheet \
  -e SPRING_DATASOURCE_USERNAME=... \
  -e SPRING_DATASOURCE_PASSWORD=... \
  checksheet-backend
```

The `Dockerfile` is at the repository root. Review it for the exact exposed port and any `ENTRYPOINT` args.

---

## 16. Environment Variables / application.properties Keys

The following keys must be configured (exact property names from the source):

| Property Key | Purpose |
|---|---|
| `aws.access_key_id` | AWS S3 access key |
| `aws.secret_access_key` | AWS S3 secret key |
| `aws.s3.region` | AWS region (e.g., `ap-south-1`) |
| `spring.datasource.url` | JDBC URL for PostgreSQL |
| `spring.datasource.username` | DB user |
| `spring.datasource.password` | DB password |
| `spring.mail.*` | SMTP configuration for `EmailService` |
| `file.upload-dir` (via `FileStorageProperties`) | Local file upload directory |
| JWT secret / expiry keys | Consumed by `JwtService` (`@Value`) |
| Firebase service-account JSON path | Consumed by `FirebaseInitializer` |
| LDAP connection settings | Consumed by `LdapAuth` / `Constants` |

> **Never commit real credentials.** Use environment variable substitution (`${ENV_VAR}`) or a secrets manager.
