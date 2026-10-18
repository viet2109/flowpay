-- One bounded, read-only projection. No IDs, payloads, credentials or amounts.
SELECT jsonb_build_object(
 'unknownTransactions',(SELECT COUNT(*) FROM payment_transactions WHERE status='UNKNOWN'),
 'processingRefunds',(SELECT COUNT(*) FROM refunds WHERE status='PROCESSING'),
 'processingIdempotency',(SELECT COUNT(*) FROM idempotency_records WHERE status='PROCESSING'),
 'refundInvariantViolations',(SELECT COUNT(*) FROM payment_intents WHERE refunded_amount_minor<0
   OR refund_reserved_minor<0 OR refunded_amount_minor+refund_reserved_minor>amount_minor),
 'unbalancedPostings',(SELECT COUNT(*) FROM (SELECT ledger_transaction_id FROM ledger_entries
   GROUP BY ledger_transaction_id HAVING SUM(CASE WHEN direction='DEBIT' THEN amount_minor ELSE -amount_minor END)<>0) x),
 'duplicatePostings',(SELECT COUNT(*) FROM (SELECT reference_type,reference_id,posting_type FROM ledger_transactions
   GROUP BY reference_type,reference_id,posting_type HAVING COUNT(*)>1) x),
 'outboxUnpublished',(SELECT COUNT(*) FROM outbox_events WHERE status<>'PUBLISHED'),
 'webhookDead',(SELECT COUNT(*) FROM webhook_deliveries WHERE status='DEAD'),
 'expiredDeliveryLeases',(SELECT COUNT(*) FROM webhook_deliveries WHERE status='DELIVERING' AND lease_expires_at<=CURRENT_TIMESTAMP),
 'openDeliveryAttempts',(SELECT COUNT(*) FROM webhook_delivery_attempts WHERE finished_at IS NULL),
 'payments',(SELECT COUNT(*) FROM payment_intents),
 'refunds',(SELECT COUNT(*) FROM refunds),
 'postings',(SELECT COUNT(*) FROM ledger_transactions),
 'webhookEvents',(SELECT COUNT(*) FROM webhook_events),
 'delivered',(SELECT COUNT(*) FROM webhook_deliveries WHERE status='DELIVERED')
);
