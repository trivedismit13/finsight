ALTER TABLE category_budgets ADD COLUMN is_alert_sent BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE notifications ADD COLUMN next_attempt_at TIMESTAMP NULL;
