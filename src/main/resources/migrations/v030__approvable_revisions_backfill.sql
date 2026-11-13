INSERT INTO approvable_revisions (created_at_ms, approvable_id, seq, revision, context, revised_by, via_service)
SELECT created_at_ms, id, 1, NULL, approval_context, requested_by, requested_via_service FROM approvables
