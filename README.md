# FinSight Backend

FinSight is a company expense operations platform built with Spring Boot. It provides robust capabilities for managing company expenses, budgets, async approvals, asynchronous report generation, and reliable email notifications.

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

## Authentication & Security
FinSight uses a dual-token JWT + sessionless architecture. Authentication uses signed JWTs instead of server-side HTTP sessions.
- **Access Token**: Short-lived (15 minutes). Used as a Bearer token in the `Authorization` header for API access.
- **Refresh Token**: Long-lived (7 days). Stored securely in the database as an SHA-256 hash. 
  - **Rotation**: Refresh tokens are rotated atomically upon every use.
  - **Theft Detection**: If a revoked refresh token is reused, the system detects a potential replay attack and proactively revokes **all** active sessions for that user.
- **Account Lockout**: The system enforces rate limiting on logins. 5 consecutive failed login attempts will lock the account for 15 minutes. Lockout state is durable (stored in the database). Disabled/locked users are blocked from API access even if their JWT is structurally valid.
- **Role/Scope Authorization**: Explicit RBAC roles.
- **Ownership Isolation**: Employees can only see their own data. Managers can only see data for their direct reports.
- **Optimistic Locking**: `@Version` used on expenses to safely handle concurrent updates.
- **Idempotency**: API endpoints support idempotency via the `Idempotency-Key` header with database-backed unique constraints.

## Roles
- **Employee**: Creates and submits expenses.
- **Manager**: Reviews, approves, or rejects expenses for their direct reports.
- **Finance Admin**: Company-wide financial governance, processes approved expenses, manages budgets and users, generates analytics and reports, and oversees operational systems (like dead-letter queues).

## Workflow
1. **Create**: Employee creates an expense (status `DRAFT`).
2. **Submit**: Employee submits the expense (status `PENDING_APPROVAL`).
3. **Approve/Reject**: Manager reviews and approves (`APPROVED`) or rejects (`REJECTED`) the expense. (A rejected expense can be edited and resubmitted by the employee).
4. **Process**: Finance Admin processes the approved expense (`PROCESSED`).
5. **Analyze**: Finance Admin views company-wide spending analytics based on finalized (Approved/Processed) expenses.
6. **Report**: Finance Admin generates CSV reports asynchronously.

## Asynchronous Architecture & Reliability
FinSight relies on Spring-managed executors for asynchronous work, strictly avoiding external messaging dependencies (like Kafka or RabbitMQ) while maintaining durability.

### Notification System
- **Worker Pool**: 4 threads managed by Spring `ThreadPoolTaskExecutor`.
- **Durability**: Notifications are persisted to the database in `PENDING` state *before* being enqueued in memory.
- **AFTER_COMMIT**: Dispatch only happens after the underlying database transaction successfully commits.
- **Bounded Queue**: Bounded in-memory `LinkedBlockingQueue` (capacity 10,000) for immediate dispatch.
- **Retry, Backoff, Jitter**: Failed notifications are retried up to 3 times using exponential backoff (1s to 30s) and jitter (500ms) to prevent thundering herds.
- **Dead Letter Queue (DLQ)**: If all retries fail, the notification transitions to `DEAD_LETTER` status for operational review by a Finance Admin.
- **Recovery**: A scheduled polling job recovers `PENDING` notifications that dropped from memory (e.g., due to a crash) and resets `PROCESSING` notifications that exceed the processing timeout (5 minutes).

### Report Generation
- **Execution**: Managed by a dedicated executor.
- **Durability**: Status is tracked in the `report_jobs` table (`PENDING`, `COMPLETED`, `FAILED`).
- **Completion**: Generates a CSV file stored temporarily on the server filesystem. Failures persist a `failureReason`.
- **Partial Cleanup**: Aborted jobs clean up partial temporary files automatically.

## Observability & Audit
- **Audit**: Comprehensive audit logging captures every state change (expense created, approved, rejected, processed, budget updated, role changed, report generated, etc.).
- **MDC Correlation**: Every request generates a unique correlation ID that traverses asynchronous boundaries (threads) for end-to-end tracing in logs.
- **Metrics**: Hikari connection pool metrics, counter metrics for notifications, reports, and API errors.
