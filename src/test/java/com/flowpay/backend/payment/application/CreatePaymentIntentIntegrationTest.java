package com.flowpay.backend.payment.application;

import com.flowpay.backend.common.error.ApiException;
import com.flowpay.backend.common.error.ErrorCode;
import com.flowpay.backend.common.security.MerchantApiPrincipal;
import com.flowpay.backend.payment.domain.PaymentStatus;
import com.flowpay.backend.testing.PostgresIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
class CreatePaymentIntentIntegrationTest extends PostgresIntegrationTest {

    private static final Instant CREATED_AT = Instant.parse("2026-08-30T09:00:00Z");

    @Autowired
    private CreatePaymentIntentService service;

    @Autowired
    private PaymentIntentRepository paymentIntentRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanData() {
        jdbcTemplate.update(
                "TRUNCATE TABLE payment_transactions, payment_intents, merchant_members, "
                        + "merchant_api_keys, refresh_tokens, merchants, users RESTART IDENTITY CASCADE"
        );
    }

    @Test
    void shouldPersistOwnershipAndAllowDuplicateOrderId() {
        long merchantId = insertActiveMerchant("mrc_create_payment");
        MerchantApiPrincipal principal = new MerchantApiPrincipal(
                "mrc_create_payment",
                "key_create_payment"
        );
        CreatePaymentIntentCommand command = new CreatePaymentIntentCommand(
                principal,
                50_000L,
                "VND",
                "ORDER-DUPLICATE",
                "Application-level creation"
        );

        CreatePaymentIntentResult first = service.create(command);
        CreatePaymentIntentResult second = service.create(command);

        assertThat(first.publicId()).startsWith("pi_").hasSize(29);
        assertThat(second.publicId()).startsWith("pi_").hasSize(29).isNotEqualTo(first.publicId());
        assertThat(first.status()).isEqualTo(PaymentStatus.CREATED);
        assertThat(first.refundedAmountMinor()).isZero();
        assertThat(first.refundReservedAmountMinor()).isZero();
        assertThat(first.orderId()).isEqualTo("ORDER-DUPLICATE");

        List<String> paymentIds = paymentIntentRepository.searchByMerchant(merchantId).stream()
                .map(payment -> payment.publicId())
                .toList();
        assertThat(paymentIds).containsExactlyInAnyOrder(first.publicId(), second.publicId());

        List<StoredPayment> stored = jdbcTemplate.query(
                """
                SELECT merchant_id, merchant_order_id, status,
                       refunded_amount_minor, refund_reserved_minor
                FROM payment_intents
                WHERE merchant_order_id = ?
                """,
                (resultSet, rowNumber) -> new StoredPayment(
                        resultSet.getLong("merchant_id"),
                        resultSet.getString("merchant_order_id"),
                        resultSet.getString("status"),
                        resultSet.getLong("refunded_amount_minor"),
                        resultSet.getLong("refund_reserved_minor")
                ),
                "ORDER-DUPLICATE"
        );
        assertThat(stored).hasSize(2).allSatisfy(payment -> {
            assertThat(payment.merchantId()).isEqualTo(merchantId);
            assertThat(payment.orderId()).isEqualTo("ORDER-DUPLICATE");
            assertThat(payment.status()).isEqualTo("CREATED");
            assertThat(payment.refundedAmountMinor()).isZero();
            assertThat(payment.refundReservedAmountMinor()).isZero();
        });
    }

    @ParameterizedTest
    @ValueSource(longs = {0L, -1L})
    void shouldRejectNonPositiveAmountWithoutPersisting(long amountMinor) {
        String merchantPublicId = "mrc_create_invalid_amount_" + Math.abs(amountMinor);
        insertActiveMerchant(merchantPublicId);

        assertThatThrownBy(() -> service.create(command(
                merchantPublicId,
                amountMinor,
                "VND"
        )))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("amountMinor must be positive");

        assertThat(paymentIntentRepository.searchByMerchant(
                merchantId(merchantPublicId)
        )).isEmpty();
    }

    @Test
    void shouldRejectInvalidCurrencyWithoutPersisting() {
        String merchantPublicId = "mrc_create_invalid_currency";
        insertActiveMerchant(merchantPublicId);

        assertThatThrownBy(() -> service.create(command(
                merchantPublicId,
                50_000L,
                "NOT-A-CURRENCY"
        ))).isInstanceOf(IllegalArgumentException.class);

        assertThat(paymentIntentRepository.searchByMerchant(
                merchantId(merchantPublicId)
        )).isEmpty();
    }

    @Test
    void shouldRejectSuspendedMerchantWithoutPersisting() {
        String merchantPublicId = "mrc_create_suspended";
        long merchantId = insertActiveMerchant(merchantPublicId);
        jdbcTemplate.update(
                "UPDATE merchants SET status = 'SUSPENDED' WHERE id = ?",
                merchantId
        );

        assertThatThrownBy(() -> service.create(command(
                merchantPublicId,
                50_000L,
                "VND"
        ))).isInstanceOfSatisfying(ApiException.class, exception ->
                assertThat(exception.code()).isEqualTo(ErrorCode.MERCHANT_SUSPENDED)
        );

        assertThat(paymentIntentRepository.searchByMerchant(merchantId)).isEmpty();
    }

    private static CreatePaymentIntentCommand command(
            String merchantPublicId,
            long amountMinor,
            String currency
    ) {
        return new CreatePaymentIntentCommand(
                new MerchantApiPrincipal(merchantPublicId, "key_create_payment"),
                amountMinor,
                currency,
                "ORDER-VALIDATION",
                "Application validation"
        );
    }

    private long merchantId(String publicId) {
        return jdbcTemplate.queryForObject(
                "SELECT id FROM merchants WHERE public_id = ?",
                Long.class,
                publicId
        );
    }

    private long insertActiveMerchant(String publicId) {
        Long id = jdbcTemplate.queryForObject(
                """
                INSERT INTO merchants (public_id, name, status, created_at, updated_at, version)
                VALUES (?, ?, 'ACTIVE', ?, ?, 0)
                RETURNING id
                """,
                Long.class,
                publicId,
                "Create Payment Store",
                CREATED_AT.atOffset(ZoneOffset.UTC),
                CREATED_AT.atOffset(ZoneOffset.UTC)
        );
        return id;
    }

    private record StoredPayment(
            long merchantId,
            String orderId,
            String status,
            long refundedAmountMinor,
            long refundReservedAmountMinor
    ) {
    }
}
