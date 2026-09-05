-- Phase 1: Add manager_id to users and establish constraints
ALTER TABLE users ADD COLUMN manager_id BIGINT;
ALTER TABLE users ADD CONSTRAINT fk_user_manager FOREIGN KEY (manager_id) REFERENCES users(user_id) ON DELETE SET NULL;

-- Rename existing users and update their roles (using standard finsight.com for tests)
UPDATE users SET name = 'Finance Admin', role = 'FINANCE_ADMIN', email = 'admin@finsight.com' WHERE email = 'admin@finsight.com';
UPDATE users SET name = 'Manager User', role = 'MANAGER', email = 'manager@finsight.com' WHERE email = 'analyst@finsight.com';
UPDATE users SET name = 'Employee User', role = 'EMPLOYEE', email = 'employee@finsight.com' WHERE email = 'viewer@finsight.com';

-- Insert additional seed users
-- Using the same bcrypt hash for 'password' as V3 seed data
INSERT INTO users (name, email, password, role) VALUES
('Manager Bob', 'bob@example.com', '$2a$10$wT0/K5.K.B4m.hC9x4.3L.nK31I/A4G7.F94Y67tZ9jV80xO7Qo1u', 'MANAGER'),
('Employee Priya', 'priya@example.com', '$2a$10$wT0/K5.K.B4m.hC9x4.3L.nK31I/A4G7.F94Y67tZ9jV80xO7Qo1u', 'EMPLOYEE'),
('Employee Aman', 'aman@example.com', '$2a$10$wT0/K5.K.B4m.hC9x4.3L.nK31I/A4G7.F94Y67tZ9jV80xO7Qo1u', 'EMPLOYEE'),
('Employee Neha', 'neha@example.com', '$2a$10$wT0/K5.K.B4m.hC9x4.3L.nK31I/A4G7.F94Y67tZ9jV80xO7Qo1u', 'EMPLOYEE');

-- Assign managers
-- manager@finsight.com manages employee@finsight.com, Priya, Aman
UPDATE users 
SET manager_id = (SELECT user_id FROM (SELECT user_id FROM users WHERE email = 'manager@finsight.com') AS subquery)
WHERE email IN ('employee@finsight.com', 'priya@example.com', 'aman@example.com');

-- Bob manages Neha
UPDATE users 
SET manager_id = (SELECT user_id FROM (SELECT user_id FROM users WHERE email = 'bob@example.com') AS subquery)
WHERE email IN ('neha@example.com');
