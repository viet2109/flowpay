package com.flowpay.backend.payment.application;

import com.flowpay.backend.common.money.Money;
import com.flowpay.backend.merchant.application.ActiveMerchantSnapshot;
import com.flowpay.backend.payment.domain.PaymentIntent;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;

@Service
@RequiredArgsConstructor
public class CreatePaymentIntentService {

    private final PaymentMerchantResolver merchantResolver;
    private final PaymentIntentRepository paymentIntentRepository;
    private final PaymentIntentPublicIdGenerator publicIdGenerator;
    private final Clock clock;

    @Transactional
    public CreatePaymentIntentResult create(CreatePaymentIntentCommand command) {
        ActiveMerchantSnapshot merchant = merchantResolver.resolve(command.merchantContext());
        Money amount = requirePositiveAmount(command.amountMinor(), command.currency());
        PaymentIntent paymentIntent = PaymentIntent.create(
                publicIdGenerator.nextId(),
                merchant.internalId(),
                command.orderId(),
                command.description(),
                amount,
                clock.instant()
        );
        return toResult(paymentIntentRepository.save(paymentIntent));
    }

    private static Money requirePositiveAmount(long amountMinor, String currency) {
        Money amount = Money.of(amountMinor, currency);
        if (!amount.isPositive()) {
            throw new IllegalArgumentException("amountMinor must be positive");
        }
        return amount;
    }

    private static CreatePaymentIntentResult toResult(PaymentIntent paymentIntent) {
        return new CreatePaymentIntentResult(
                paymentIntent.publicId(),
                paymentIntent.merchantOrderId(),
                paymentIntent.amount().amountMinor(),
                paymentIntent.amount().currency().getCurrencyCode(),
                paymentIntent.status(),
                paymentIntent.refundedAmount().amountMinor(),
                paymentIntent.refundReservedAmount().amountMinor(),
                paymentIntent.description(),
                paymentIntent.createdAt()
        );
    }
}
