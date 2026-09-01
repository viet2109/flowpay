package com.flowpay.backend.ledger.application;

import com.flowpay.backend.ledger.domain.LedgerPostingType;
import com.flowpay.backend.ledger.domain.LedgerTransaction;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Objects;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class LedgerReversalService {

    private static final String NOT_FOUND_MESSAGE =
            "The original ledger transaction was not found";
    private static final String NOT_REVERSIBLE_MESSAGE =
            "A reversal ledger transaction cannot be reversed";
    private static final String UNSAFE_ORIGINAL_MESSAGE =
            "The original ledger transaction cannot be safely reversed";

    private final LedgerTransactionRepository transactionRepository;
    private final LedgerTransactionPublicIdGenerator publicIdGenerator;
    private final LedgerPostingService postingService;
    private final Clock clock;

    @Transactional
    public LedgerPostingResult reverse(ReverseLedgerTransactionCommand command) {
        ReverseLedgerTransactionCommand value = Objects.requireNonNull(
                command,
                "command must not be null"
        );
        LedgerTransaction original = loadOriginal(
                value.originalLedgerTransactionPublicId()
        );
        if (original.postingType() == LedgerPostingType.REVERSAL) {
            throw new LedgerTransactionNotReversibleException(NOT_REVERSIBLE_MESSAGE);
        }

        LedgerTransaction reversal = original.reverse(
                publicIdGenerator.nextId(),
                value.description(),
                value.occurredAt(),
                clock.instant()
        );
        return postingService.claim(reversal);
    }

    private LedgerTransaction loadOriginal(String publicId) {
        Optional<LedgerTransaction> original;
        try {
            original = transactionRepository.findByPublicId(publicId);
        } catch (RuntimeException exception) {
            throw new LedgerReversalException(UNSAFE_ORIGINAL_MESSAGE, exception);
        }
        return original.orElseThrow(
                () -> new LedgerTransactionNotFoundException(NOT_FOUND_MESSAGE)
        );
    }
}
