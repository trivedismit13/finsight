-- Drop the global unique constraint on idempotency_key
-- Note: MySQL names the unique constraint/index identically to the column if not specified.
ALTER TABLE financial_records DROP INDEX idempotency_key;

-- Add a composite unique constraint scoping the idempotency key to the user
ALTER TABLE financial_records ADD CONSTRAINT unique_user_idempotency UNIQUE (created_by, idempotency_key);
