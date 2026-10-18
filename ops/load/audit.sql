-- Read-only, whole-owned-project audit, including partial workflows under429.
SELECT jsonb_build_object(
 'payments', (SELECT COUNT(*) FROM payment_intents),
 'confirmed', (SELECT COUNT(*) FROM payment_transactions WHERE status='SUCCEEDED'),
 'refunds', (SELECT COUNT(*) FROM refunds),
 'postings', (SELECT COUNT(*) FROM ledger_transactions),
 'events', (SELECT COUNT(*) FROM webhook_events),
 'deliveries', (SELECT COUNT(*) FROM webhook_deliveries),
 'invariants',
 NOT EXISTS(SELECT 1 FROM payment_intents p WHERE p.amount_minor<>100000 OR p.currency<>'VND'
   OR p.refunded_amount_minor<0 OR p.refund_reserved_minor<0
   OR p.refunded_amount_minor+p.refund_reserved_minor>p.amount_minor
   OR p.refunded_amount_minor<>COALESCE((SELECT SUM(r.amount_minor) FROM refunds r WHERE r.payment_intent_id=p.id AND r.status='SUCCEEDED'),0)
   OR p.refund_reserved_minor<>COALESCE((SELECT SUM(r.amount_minor) FROM refunds r WHERE r.payment_intent_id=p.id AND r.status='PROCESSING'),0))
 AND NOT EXISTS(SELECT 1 FROM payment_transactions WHERE status<>'SUCCEEDED' OR attempt_no<>1)
 AND NOT EXISTS(SELECT 1 FROM payment_transactions GROUP BY payment_intent_id HAVING COUNT(*)<>1)
 AND NOT EXISTS(SELECT 1 FROM refunds WHERE status<>'SUCCEEDED' OR amount_minor<>1000 OR currency<>'VND')
 AND NOT EXISTS(SELECT 1 FROM refunds GROUP BY payment_intent_id HAVING COUNT(*)<>1)
 AND NOT EXISTS(SELECT 1 FROM payment_intents p WHERE
   (p.status='CREATED' AND EXISTS(SELECT 1 FROM payment_transactions t WHERE t.payment_intent_id=p.id))
   OR (p.status NOT IN ('CREATED','SUCCEEDED','PARTIALLY_REFUNDED'))
   OR (p.status='SUCCEEDED' AND p.refunded_amount_minor<>0)
   OR (p.status='PARTIALLY_REFUNDED' AND p.refunded_amount_minor<>1000)
   OR (p.status<>'CREATED' AND NOT EXISTS(SELECT 1 FROM payment_transactions t WHERE t.payment_intent_id=p.id)))
 AND (SELECT COUNT(*) FROM ledger_transactions)=(SELECT COUNT(*) FROM payment_transactions)+(SELECT COUNT(*) FROM refunds)
 AND NOT EXISTS(SELECT 1 FROM ledger_transactions GROUP BY reference_type,reference_id,posting_type HAVING COUNT(*)<>1)
 AND NOT EXISTS(SELECT 1 FROM ledger_transactions t WHERE
   (SELECT COUNT(*) FROM ledger_entries e WHERE e.ledger_transaction_id=t.id)<>2
   OR (t.posting_type='PAYMENT_SUCCEEDED' AND NOT EXISTS(SELECT 1 FROM payment_intents p WHERE p.public_id=t.reference_id AND p.status IN ('SUCCEEDED','PARTIALLY_REFUNDED')))
   OR (t.posting_type='REFUND_SUCCEEDED' AND NOT EXISTS(SELECT 1 FROM refunds r WHERE r.public_id=t.reference_id AND r.status='SUCCEEDED'))
   OR t.posting_type NOT IN ('PAYMENT_SUCCEEDED','REFUND_SUCCEEDED'))
 AND NOT EXISTS(SELECT 1 FROM ledger_entries GROUP BY ledger_transaction_id
   HAVING SUM(CASE WHEN direction='DEBIT' THEN amount_minor ELSE -amount_minor END)<>0 OR COUNT(*)<>2)
 AND NOT EXISTS(SELECT 1 FROM ledger_entries e JOIN ledger_transactions t ON t.id=e.ledger_transaction_id
   WHERE e.amount_minor<>CASE WHEN t.posting_type='PAYMENT_SUCCEEDED' THEN 100000 ELSE 1000 END)
 AND (SELECT COUNT(*) FROM outbox_events)=2*((SELECT COUNT(*) FROM payment_transactions)+(SELECT COUNT(*) FROM refunds))
 AND NOT EXISTS(SELECT 1 FROM outbox_events WHERE status<>'PUBLISHED')
 AND NOT EXISTS(SELECT 1 FROM outbox_events GROUP BY aggregate_id,event_type HAVING COUNT(*)<>1)
 AND (SELECT COUNT(*) FROM webhook_events)=(SELECT COUNT(*) FROM outbox_events)
 AND NOT EXISTS(SELECT 1 FROM webhook_events e WHERE e.payload->>'id'<>e.public_id OR e.payload->>'type'<>e.event_type
   OR NOT EXISTS(SELECT 1 FROM outbox_events o WHERE o.event_id=e.source_event_id AND o.event_type=e.event_type||'.v1'))
 AND NOT EXISTS(SELECT 1 FROM webhook_events GROUP BY source_event_id HAVING COUNT(*)<>1)
 AND (SELECT COUNT(*) FROM webhook_deliveries)=3*(SELECT COUNT(*) FROM webhook_events)
 AND NOT EXISTS(SELECT 1 FROM webhook_deliveries WHERE status<>'DELIVERED')
 AND NOT EXISTS(SELECT 1 FROM webhook_deliveries GROUP BY webhook_event_id,webhook_endpoint_id HAVING COUNT(*)<>1)
 AND NOT EXISTS(SELECT 1 FROM webhook_deliveries d JOIN webhook_endpoints e ON e.id=d.webhook_endpoint_id
   WHERE d.attempt_count<>CASE WHEN e.url LIKE '%/flaky' THEN 2 ELSE 1 END
   OR d.attempt_count<>(SELECT COUNT(*) FROM webhook_delivery_attempts a WHERE a.delivery_id=d.id))
 AND NOT EXISTS(SELECT 1 FROM webhook_delivery_attempts WHERE finished_at IS NULL)
 AND NOT EXISTS(SELECT 1 FROM webhook_delivery_attempts first_attempt
   JOIN webhook_delivery_attempts second_attempt ON second_attempt.delivery_id=first_attempt.delivery_id AND second_attempt.attempt_no=2
   WHERE first_attempt.attempt_no=1 AND
     (first_attempt.http_status<>503 OR second_attempt.http_status<>204 OR second_attempt.started_at<first_attempt.finished_at+INTERVAL '10 seconds'))
 AND NOT EXISTS(SELECT 1 FROM idempotency_records WHERE status='PROCESSING')
 AND (SELECT COUNT(*) FROM webhook_endpoints WHERE status='ACTIVE')=3
);
