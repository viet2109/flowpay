package com.flowpay.backend.payment.application;

import com.flowpay.backend.payment.domain.PaymentTransaction;

import java.util.List;
import java.util.Optional;

public interface PaymentTransactionRepository {

    PaymentTransaction save(PaymentTransaction paymentTransaction);

    Optional<PaymentTransaction> findByPublicId(String publicId);

    List<PaymentTransaction> findByPaymentIntentId(long paymentIntentId);

    Optional<PaymentTransaction> findLatestByPaymentIntentId(long paymentIntentId);
}
