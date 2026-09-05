-- Using bcrypt hash for 'password'
INSERT INTO users (name, email, password, role) VALUES
('Admin User', 'admin@finsight.com', '$2a$10$wT0/K5.K.B4m.hC9x4.3L.nK31I/A4G7.F94Y67tZ9jV80xO7Qo1u', 'FINANCE_ADMIN'),
('Manager User', 'manager@finsight.com', '$2a$10$wT0/K5.K.B4m.hC9x4.3L.nK31I/A4G7.F94Y67tZ9jV80xO7Qo1u', 'MANAGER'),
('Employee User', 'employee@finsight.com', '$2a$10$wT0/K5.K.B4m.hC9x4.3L.nK31I/A4G7.F94Y67tZ9jV80xO7Qo1u', 'EMPLOYEE');
