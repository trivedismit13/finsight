ALTER TABLE expenses ADD COLUMN rejected_by BIGINT;
ALTER TABLE expenses ADD CONSTRAINT fk_expense_rejected_by FOREIGN KEY (rejected_by) REFERENCES users(user_id);
