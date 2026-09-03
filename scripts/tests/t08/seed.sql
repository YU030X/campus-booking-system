USE booking_db;

SET @qa_resource_id = 880001;
SET @qa_category_id = 880001;

DELETE FROM violation_record WHERE user_id IN (
    SELECT id FROM `user` WHERE username LIKE 't08qa\\_%' ESCAPE '\\'
) OR booking_id IN (
    SELECT id FROM booking WHERE resource_id = @qa_resource_id OR user_id IN (
        SELECT id FROM `user` WHERE username LIKE 't08qa\\_%' ESCAPE '\\'
    )
);
DELETE FROM approval_record WHERE booking_id IN (
    SELECT id FROM booking WHERE resource_id = @qa_resource_id OR user_id IN (
        SELECT id FROM `user` WHERE username LIKE 't08qa\\_%' ESCAPE '\\'
    )
);
DELETE FROM booking_slot WHERE resource_id = @qa_resource_id OR booking_id IN (
    SELECT id FROM booking WHERE resource_id = @qa_resource_id OR user_id IN (
        SELECT id FROM `user` WHERE username LIKE 't08qa\\_%' ESCAPE '\\'
    )
);
DELETE FROM booking WHERE resource_id = @qa_resource_id OR user_id IN (
    SELECT id FROM `user` WHERE username LIKE 't08qa\\_%' ESCAPE '\\'
);
DELETE FROM blacklist WHERE user_id IN (
    SELECT id FROM `user` WHERE username LIKE 't08qa\\_%' ESCAPE '\\'
);
DELETE FROM notification WHERE user_id IN (
    SELECT id FROM `user` WHERE username LIKE 't08qa\\_%' ESCAPE '\\'
);
DELETE FROM `user` WHERE username LIKE 't08qa\\_%' ESCAPE '\\';
DELETE FROM resource_closure WHERE resource_id = @qa_resource_id;
DELETE FROM resource_time_rule WHERE resource_id = @qa_resource_id;
DELETE FROM resource WHERE id = @qa_resource_id;
DELETE FROM resource_category WHERE id = @qa_category_id;

INSERT INTO resource_category(id, name, parent_id, sort_order, deleted)
VALUES (@qa_category_id, 'T08 QA 分类', 0, 0, 0);
INSERT INTO resource(
    id, category_id, name, location, capacity, description, need_approval,
    max_advance_days, min_duration_minutes, max_duration_minutes, status, deleted
) VALUES (
    @qa_resource_id, @qa_category_id, 'T08 QA 研讨室', 'QA-101', 20,
    '仅用于 T08 headless 验收', 0, 7, 30, 120, 1, 0
);
INSERT INTO resource_time_rule(resource_id, day_of_week, start_time, end_time, deleted)
VALUES (@qa_resource_id, WEEKDAY(DATE_ADD(CURDATE(), INTERVAL 1 DAY)) + 1, '08:00:00', '20:00:00', 0);
