CREATE INDEX idx_fr_type ON financial_records(type);
CREATE INDEX idx_fr_category ON financial_records(category);
CREATE INDEX idx_fr_record_date ON financial_records(record_date);
CREATE INDEX idx_fr_created_by ON financial_records(created_by);
CREATE INDEX idx_fr_is_deleted ON financial_records(is_deleted);
