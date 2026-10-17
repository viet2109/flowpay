-- Whole owned DB; SELECT only, including both asynchronous consumers/history.
SELECT jsonb_build_object(
 'payments', (SELECT COUNT(*) FROM payment_intents),
 'refunds', (SELECT COUNT(*) FROM refunds),
 'postings', (SELECT COUNT(*) FROM ledger_transactions),
 'events', (SELECT COUNT(*) FROM webhook_events),
 'deliveries', (SELECT COUNT(*) FROM webhook_deliveries),
 'attempts', (SELECT COUNT(*) FROM webhook_delivery_attempts),
 'leaseRecoveries', (SELECT COUNT(*) FROM webhook_delivery_attempts WHERE error_message='DELIVERY_LEASE_EXPIRED'),
 'invariants',
 NOT EXISTS(SELECT 1 FROM payment_intents p WHERE p.amount_minor<>100000 OR p.currency<>'VND'
   OR p.status<>'PARTIALLY_REFUNDED' OR p.refunded_amount_minor<>1000 OR p.refund_reserved_minor<>0
   OR p.refunded_amount_minor+p.refund_reserved_minor>p.amount_minor
   OR p.refunded_amount_minor<>(SELECT COALESCE(SUM(r.amount_minor),0) FROM refunds r WHERE r.payment_intent_id=p.id AND r.status='SUCCEEDED'))
 AND (SELECT COUNT(*) FROM payment_transactions)=(SELECT COUNT(*) FROM payment_intents)
 AND NOT EXISTS(SELECT 1 FROM payment_transactions WHERE status<>'SUCCEEDED' OR attempt_no<>1)
 AND NOT EXISTS(SELECT 1 FROM payment_transactions GROUP BY payment_intent_id HAVING COUNT(*)<>1)
 AND (SELECT COUNT(*) FROM refunds)=(SELECT COUNT(*) FROM payment_intents)
 AND NOT EXISTS(SELECT 1 FROM refunds WHERE status<>'SUCCEEDED' OR amount_minor<>1000 OR currency<>'VND')
 AND NOT EXISTS(SELECT 1 FROM refunds GROUP BY payment_intent_id HAVING COUNT(*)<>1)
 AND (SELECT COUNT(*) FROM ledger_transactions)=2*(SELECT COUNT(*) FROM payment_intents)
 AND NOT EXISTS(SELECT 1 FROM ledger_transactions GROUP BY reference_type,reference_id,posting_type HAVING COUNT(*)<>1)
 AND NOT EXISTS(SELECT 1 FROM ledger_transactions t WHERE
   (t.posting_type='PAYMENT_SUCCEEDED' AND NOT EXISTS(SELECT 1 FROM payment_intents p WHERE p.public_id=t.reference_id))
   OR (t.posting_type='REFUND_SUCCEEDED' AND NOT EXISTS(SELECT 1 FROM refunds r WHERE r.public_id=t.reference_id))
   OR t.posting_type NOT IN ('PAYMENT_SUCCEEDED','REFUND_SUCCEEDED'))
 AND NOT EXISTS(SELECT 1 FROM ledger_entries GROUP BY ledger_transaction_id
   HAVING COUNT(*)<>2 OR SUM(CASE WHEN direction='DEBIT' THEN amount_minor ELSE -amount_minor END)<>0)
 AND (SELECT COUNT(*) FROM ledger_entries)=2*(SELECT COUNT(*) FROM ledger_transactions)
 AND NOT EXISTS(SELECT 1 FROM ledger_entries e JOIN ledger_transactions t ON t.id=e.ledger_transaction_id
   WHERE t.currency<>'VND' OR e.amount_minor<>CASE WHEN t.posting_type='PAYMENT_SUCCEEDED' THEN 100000 ELSE 1000 END)
 AND (SELECT COUNT(*) FROM outbox_events)=4*(SELECT COUNT(*) FROM payment_intents)
 AND NOT EXISTS(SELECT 1 FROM outbox_events WHERE status<>'PUBLISHED')
 AND NOT EXISTS(SELECT 1 FROM outbox_events GROUP BY aggregate_id,event_type HAVING COUNT(*)<>1)
 AND (SELECT COUNT(*) FROM webhook_events)=(SELECT COUNT(*) FROM outbox_events)
 AND NOT EXISTS(SELECT 1 FROM webhook_events e WHERE e.payload->>'id'<>e.public_id OR e.payload->>'type'<>e.event_type
   OR NOT EXISTS(SELECT 1 FROM outbox_events o WHERE o.event_id=e.source_event_id AND o.event_type=e.event_type||'.v1'))
 AND NOT EXISTS(SELECT 1 FROM webhook_events GROUP BY source_event_id HAVING COUNT(*)<>1)
 AND (SELECT COUNT(*) FROM webhook_deliveries)=2*(SELECT COUNT(*) FROM payment_intents)
 AND NOT EXISTS(SELECT 1 FROM webhook_deliveries WHERE status<>'DELIVERED' OR lease_expires_at IS NOT NULL)
 AND NOT EXISTS(SELECT 1 FROM webhook_deliveries GROUP BY webhook_event_id,webhook_endpoint_id HAVING COUNT(*)<>1)
 AND NOT EXISTS(SELECT 1 FROM webhook_deliveries d WHERE d.attempt_count<>(SELECT COUNT(*) FROM webhook_delivery_attempts a WHERE a.delivery_id=d.id))
 AND NOT EXISTS(SELECT 1 FROM webhook_delivery_attempts WHERE finished_at IS NULL)
 AND NOT EXISTS(SELECT 1 FROM webhook_delivery_attempts a JOIN webhook_delivery_attempts b
   ON a.delivery_id=b.delivery_id AND b.attempt_no=a.attempt_no+1
   WHERE b.started_at<a.finished_at+INTERVAL '10 seconds')
 AND NOT EXISTS(SELECT 1 FROM webhook_delivery_attempts WHERE error_message='DELIVERY_LEASE_EXPIRED' AND (http_status IS NOT NULL OR duration_ms IS NOT NULL))
 AND NOT EXISTS(SELECT 1 FROM idempotency_records WHERE status<>'COMPLETED')
 AND (SELECT COUNT(*) FROM webhook_endpoints WHERE status='ACTIVE')=1
);
