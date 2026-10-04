package com.flowpay.backend.ledger.application;

import com.flowpay.backend.common.observability.MetricsRecorder;
import com.flowpay.backend.ledger.domain.LedgerPostingType;
import io.micrometer.core.instrument.Tags;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;

@Component
@RequiredArgsConstructor
public class LedgerMetrics {
    private final MetricsRecorder recorder;

    public LedgerPostingResult observeClaim(LedgerPostingType type, Supplier<LedgerPostingResult> claim) {
        try {
            LedgerPostingResult result = claim.get();
            decision(type, result.outcome() == LedgerPostingOutcome.CREATED ? "CREATED" : "DUPLICATE");
            if (result.outcome() == LedgerPostingOutcome.CREATED) {
                recorder.incrementAfterCommit("flowpay.ledger.postings", Tags.of("type", type.name()));
            }
            return result;
        } catch (LedgerPostingConflictException exception) {
            decision(type, "CONFLICT");
            throw exception;
        } catch (RuntimeException exception) {
            decision(type, "FAILURE");
            throw exception;
        }
    }

    private void decision(LedgerPostingType type, String outcome) {
        recorder.increment("flowpay.ledger.posting.decisions", Tags.of("type", type.name(), "outcome", outcome));
    }
}
