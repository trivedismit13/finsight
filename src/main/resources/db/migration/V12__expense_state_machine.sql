-- Phase 2: Expense lifecycle/state machine

-- Rename table
RENAME TABLE financial_records TO expenses;

-- Add new columns
ALTER TABLE expenses 
    ADD COLUMN currency VARCHAR(3) NOT NULL DEFAULT 'INR',
    ADD COLUMN status VARCHAR(50) NOT NULL DEFAULT 'DRAFT',
    ADD COLUMN submitted_at DATETIME(6),
    ADD COLUMN approved_at DATETIME(6),
    ADD COLUMN approved_by BIGINT,
    ADD COLUMN rejected_at DATETIME(6),
    ADD COLUMN rejection_reason TEXT;

-- Add foreign key constraint for approved_by
ALTER TABLE expenses
    ADD CONSTRAINT fk_expense_approved_by FOREIGN KEY (approved_by) REFERENCES users(user_id) ON DELETE SET NULL;

-- Remove old column
ALTER TABLE expenses DROP COLUMN type;

-- Drop old indexes
DROP INDEX idx_fr_dashboard ON expenses;
DROP INDEX idx_fr_user_reports ON expenses;
DROP INDEX idx_fr_budget_filter ON expenses;

-- Create new indexes
CREATE INDEX idx_expense_dashboard ON expenses (is_deleted, expense_date, category);
CREATE INDEX idx_expense_user_reports ON expenses (created_by, is_deleted, expense_date);
CREATE INDEX idx_expense_budget_filter ON expenses (category, status, is_deleted, expense_date);
