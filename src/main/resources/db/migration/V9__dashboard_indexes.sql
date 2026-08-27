-- Drop the naive single-column indexes which offer poor selectivity for composite dashboard queries
DROP INDEX idx_fr_type ON financial_records;
DROP INDEX idx_fr_category ON financial_records;
DROP INDEX idx_fr_record_date ON financial_records;
DROP INDEX idx_fr_created_by ON financial_records;
DROP INDEX idx_fr_is_deleted ON financial_records;

-- Justification: Dashboard and Analytics Aggregations filter by deleted state and record_date range.
-- Placing equality conditions (is_deleted) first, followed by the range condition (record_date).
CREATE INDEX idx_fr_dashboard ON financial_records(is_deleted, record_date, category);

-- Justification: User-scoped queries and reports filter by created_by, is_deleted, and record_date range.
CREATE INDEX idx_fr_user_reports ON financial_records(created_by, is_deleted, record_date);

-- Justification: Budget vs Actual filters by category, type (EXPENSE), is_deleted, and record_date range.
-- Category and Type are high-selectivity equality conditions here.
CREATE INDEX idx_fr_budget_filter ON financial_records(category, type, is_deleted, record_date);
