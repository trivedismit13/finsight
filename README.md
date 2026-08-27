# FinSight Backend

FinSight is a financial data management and reporting API built with Spring Boot. It provides robust capabilities for managing financial records, asynchronous report generation, and reliable email notifications.

## System Requirements
- **Java**: 21 (or 17+)
- **Database**: MySQL 8+
- **Build Tool**: Maven

## Database Setup
1. Install and start MySQL.
2. Create the target database:
   ```sql
   CREATE DATABASE finsight;
   ```
3. Flyway migrations are enabled (`spring.flyway.enabled=true`). The database schema will be automatically initialized and updated when the application starts.

## Environment Variables
The application strictly enforces externalized secrets. You **must** set the following environment variables before starting the application:

- `DB_PASSWORD`: The password for the MySQL `root` user (or configured user).
- `JWT_SECRET`: A secure base64-encoded secret key used for signing JWT access tokens. Must be sufficiently long (e.g., 256-bit) for HMAC-SHA256.

## Running the Application
```bash
# Compile and run tests
./mvnw clean test

# Run the application
DB_PASSWORD=yourdbpassword JWT_SECRET=yourjwtsecret ./mvnw spring-boot:run
```

## Authentication Flow
FinSight uses a dual-token JWT + sessionless architecture. Authentication uses signed JWTs instead of server-side HTTP sessions, so authentication state does not depend on a shared HTTP session store. (Note: A database lookup is still performed per request to load the current user's authorization state and ensure they are active/not deleted, but this is distinct from maintaining server-side session state).
- **Access Token**: Short-lived (15 minutes). Used as a Bearer token in the `Authorization` header for API access.
- **Refresh Token**: Long-lived (7 days). Stored securely in the database as an SHA-256 hash. 
  - **Rotation**: Refresh tokens are rotated upon every use.
  - **Theft Detection**: If a revoked refresh token is reused, the system detects a potential replay attack and proactively revokes **all** active sessions for that user.

**Account Lockout**: The system enforces rate limiting on logins. 5 consecutive failed login attempts will lock the account for 15 minutes. Lockout state is durable (stored in the database).

## Role Matrix
All financial records are strongly isolated by ownership (`createdBy = authenticatedUser`).

| Role | Capabilities |
| :--- | :--- |
| **VIEWER** | Read-only access to their own financial records and dashboards. |
| **ANALYST** | Inherits VIEWER capabilities. Can Create, Update, and Delete their own records. Can request asynchronous report exports. |
| **ADMIN** | Global visibility. Cannot create/edit records. Can manage users and access system-wide audit logs. |

*(Note: Self-registration via `/api/auth/register` automatically assigns the VIEWER role. Role escalation requires an ADMIN.)*

## API Endpoints Overview

### Authentication
- `POST /api/auth/register`: Register a new user (VIEWER).
- `POST /api/auth/login`: Authenticate and receive tokens.
- `POST /api/auth/refresh`: Rotate refresh token and get a new access token.
- `POST /api/auth/logout`: Revoke the provided refresh token.

### Financial Records (Isolated by User)
- `GET /api/records`: Paginated list of records. Supports filtering.
- `POST /api/records`: Create a new record (ANALYST only). Supports idempotency via `Idempotency-Key` header.
- `GET /api/records/{id}`: Retrieve a specific record.
- `PUT /api/records/{id}`: Update a record. (Soft deletes previous versions for auditability).
- `DELETE /api/records/{id}`: Soft delete a record.

### Reports (Asynchronous)
- `POST /api/reports/export/{userId}`: Enqueue a report generation job (ANALYST only). Returns `202 Accepted`.
- `GET /api/reports/download/{id}`: Download a completed report (CSV format).

### Admin
- `GET /api/admin/users`: Manage user roles and status.
- `GET /api/admin/audit`: View system-wide audit logs.

## Asynchronous Architecture
FinSight relies on Spring-managed executors for asynchronous work, strictly avoiding external messaging dependencies (like Kafka or RabbitMQ) while maintaining durability.

### Notification System
- **Worker Pool**: 4 threads managed by Spring `ThreadPoolTaskExecutor`.
- **Queue**: Bounded in-memory `LinkedBlockingQueue` (capacity 10,000) for immediate dispatch.
- **Durability**: Notifications are persisted to the database in `PENDING` state *before* being enqueued in memory.
- **Recovery**: A scheduled polling job (`poll-interval-ms=15000`) recovers `PENDING` notifications that dropped from memory (e.g., due to a crash) and resets `PROCESSING` notifications that exceed the `processing-timeout-minutes` (5 minutes).

### Report Generation
- **Execution**: Managed by `reportExecutor`.
- **Durability**: Status is tracked in the `report_jobs` table (`PENDING`, `COMPLETED`, `FAILED`).
- **Completion**: Generates a CSV file stored as a BLOB. Automatically triggers an email notification to the user upon success or failure.

## Retry Behavior
External integrations (like the Email Provider) use resilient retry strategies configured under `finsight.notification.*`:
- **Exponential Backoff**: Starts at 1 second (`base-delay-ms`), up to 30 seconds (`max-delay-ms`).
- **Jitter**: Adds up to 500ms of jitter to prevent thundering herds.
- **Max Retries**: 3 attempts.
- **Dead Lettering**: If all retries fail, the notification transitions to `DEAD_LETTER` status for manual intervention.

## Known Limitations
1. **Delay in Revocation**: Because Access Tokens do not query the database for every property, revoking a user's access (e.g., via logout or admin action) will not immediately invalidate their current Access Token if the token remains unexpired (15 minutes). However, the per-request user lookup mitigates this by allowing instant blocking of deleted or locked users.
2. **In-Memory Notification Queuing**: Under extreme load where the bounded queue (10,000 capacity) fills up, new notifications rely on the database fallback. They will be processed by the background polling thread instead of immediate execution, increasing dispatch latency.
3. **Report Storage**: Completed reports are currently stored as `LONGBLOB` in the relational database. For large-scale production deployments, this should be migrated to an object storage service (e.g., AWS S3).
