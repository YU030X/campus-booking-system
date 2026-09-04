-- T13 JMeter isolated fixture seed (idempotent, scoped to the t13jm_ prefix
-- and fixed fixture ids; deletion is fixture-owned cleanup only).
DELETE FROM booking_slot WHERE resource_id = 880001 OR booking_id IN (SELECT id FROM booking WHERE purpose LIKE 'T13 jm%');
DELETE FROM booking WHERE resource_id = 880001 OR purpose LIKE 'T13 jm%';
DELETE FROM resource_time_rule WHERE resource_id = 880001;
DELETE FROM resource_closure WHERE resource_id = 880001;
DELETE FROM resource WHERE id = 880001;
DELETE FROM resource_category WHERE id = 880001;
DELETE FROM notification WHERE user_id IN (SELECT id FROM user WHERE username LIKE 't13jm_%');
DELETE FROM blacklist WHERE user_id IN (SELECT id FROM user WHERE username LIKE 't13jm_%');
DELETE FROM user WHERE username LIKE 't13jm_%';
INSERT INTO resource_category(id, name, parent_id, sort_order, deleted) VALUES (880001, 'T13 JM 分类', 0, 0, 0);
INSERT INTO resource(id, category_id, name, location, capacity, description, need_approval, max_advance_days, min_duration_minutes, max_duration_minutes, status, deleted)
VALUES (880001, 880001, 'T13 JM 压测室', 'JM-101', 10, 'T13 jmeter isolated fixture', 0, 7, 30, 120, 1, 0);
INSERT INTO resource_time_rule(resource_id, day_of_week, start_time, end_time, deleted)
VALUES (880001, WEEKDAY(DATE_ADD(CURDATE(), INTERVAL 1 DAY)) + 1, '08:00:00', '20:00:00', 0);
