-- Local demo acceptance only. Before this run: categories=5, tickets/records/
-- participants/daily_sequences=0, users=3, roles=3, permissions=14.
-- Reserved E2E prefixes only; never run against production.
START TRANSACTION;
DELETE FROM ticket_participant WHERE ticket_id IN
  (SELECT id FROM ticket WHERE LOCATE('E2E 联调工单 ', title) = 1);
DELETE FROM ticket_record WHERE ticket_id IN
  (SELECT id FROM ticket WHERE LOCATE('E2E 联调工单 ', title) = 1);
DELETE FROM ticket WHERE LOCATE('E2E 联调工单 ', title) = 1;
DELETE FROM iam_user_role WHERE user_id IN
  (SELECT id FROM iam_user WHERE LOCATE('E2E_ticket_isolation_', username) = 1);
DELETE FROM iam_user WHERE LOCATE('E2E_ticket_isolation_', username) = 1;
DELETE FROM ticket_category WHERE LOCATE('E2E_STAGE2_CATEGORY_', name) = 1
  AND NOT EXISTS (SELECT 1 FROM ticket WHERE category_id = ticket_category.id);
-- Restoring this date is valid because its initial sequence table was empty.
DELETE FROM ticket_daily_sequence WHERE business_date = '2026-09-29'
  AND NOT EXISTS (SELECT 1 FROM ticket WHERE ticket_no LIKE 'FD-20260929-%');
COMMIT;
SELECT (SELECT COUNT(*) FROM ticket_category) AS categories,
  (SELECT COUNT(*) FROM ticket) AS tickets,
  (SELECT COUNT(*) FROM ticket_record) AS records,
  (SELECT COUNT(*) FROM ticket_participant) AS participants,
  (SELECT COUNT(*) FROM ticket_daily_sequence) AS daily_sequences,
  (SELECT COUNT(*) FROM iam_user) AS users,
  (SELECT COUNT(*) FROM iam_role) AS roles,
  (SELECT COUNT(*) FROM iam_permission) AS permissions;
