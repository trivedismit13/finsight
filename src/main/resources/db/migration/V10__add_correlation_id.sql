ALTER TABLE report_jobs ADD COLUMN correlation_id VARCHAR(36);
ALTER TABLE notifications ADD COLUMN correlation_id VARCHAR(36);
