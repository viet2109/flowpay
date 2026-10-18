-- Optional technical metadata only. Legacy writers/readers remain compatible.
ALTER TABLE outbox_events ADD COLUMN traceparent VARCHAR(55) NULL;
