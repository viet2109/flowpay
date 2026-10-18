-- First materialization context only; no backfill, dedupe/business constraint or public payload change.
ALTER TABLE webhook_events ADD COLUMN traceparent VARCHAR(55) NULL;
