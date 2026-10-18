(ns knoxx.backend.infra.db.mailbox-queries
  "Static PostgreSQL query data for actor-mailbox persistence.

   The caller owns parameter preparation, query execution and all database
   clock/locking effects. Loading this namespace only defines string values.")

(def create-entry-sql
  "Static INSERT text; execution and database-clock semantics stay in query!."
  "INSERT INTO actor_mailbox_entries
                   (id, kind, status,
                    source_actor_id, source_session_id, source_conversation_id, source_run_id, source_json,
                    target_kind, target_actor_id, target_session_id, target_conversation_id, target_run_id, target_json,
                    delivery_mode, attempts, next_at, expires_at,
                    content_ref_json, metadata_json, preview)
                   VALUES
                   ($1::uuid, $2, $3,
                    $4, $5, $6, $7, $8::jsonb,
                    $9, $10, $11, $12, $13, $14::jsonb,
                    $15, $16, $17::timestamptz, $18::timestamptz,
                    $19::jsonb, $20::jsonb, $21)
                   RETURNING *")

(def retry-eligible-sql
  "Static locked retry UPDATE; its NOW() expressions run only in the database."
  "WITH candidates AS (
                     SELECT id
                     FROM actor_mailbox_entries
                     WHERE status = ANY($1::text[])
                       AND attempts < $2
                       AND (next_at IS NULL OR next_at <= NOW())
                       AND (expires_at IS NULL OR expires_at > NOW())
                       AND ($5::uuid IS NULL OR id = $5::uuid)
                     ORDER BY created_at ASC
                     LIMIT $3
                     FOR UPDATE SKIP LOCKED
                   )
                   UPDATE actor_mailbox_entries m
                   SET status = 'pending',
                       attempts = m.attempts + 1,
                       next_at = CASE WHEN $4::int > 0 THEN NOW() + ($4::text || ' seconds')::interval ELSE NULL END,
                       last_error = NULL,
                       updated_at = NOW()
                   FROM candidates
                   WHERE m.id = candidates.id
                   RETURNING m.*")
