-- T13 vulnerable-baseline bootstrap: runs AFTER sql/V001..V005 inside the
-- ISOLATED jmeter-baseline MySQL (its own server + volume; the production
-- database is never touched). Removes only the booking_slot unique index so
-- the historical check-then-act race can produce duplicate bookings - the
-- documented docs/06 W7 narrative. No data is deleted by this script.
ALTER TABLE `booking_slot` DROP INDEX `uk_resource_slot`;
