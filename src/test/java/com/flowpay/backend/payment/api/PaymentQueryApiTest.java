package com.flowpay.backend.payment.api;

import com.flowpay.backend.common.money.Money;
import com.flowpay.backend.merchant.application.ApiKeyRepository;
import com.flowpay.backend.merchant.application.ApiKeySecretCodec;
import com.flowpay.backend.merchant.application.GeneratedApiKeySecret;
import com.flowpay.backend.merchant.application.MerchantRepository;
import com.flowpay.backend.merchant.domain.ApiKey;
import com.flowpay.backend.merchant.domain.Merchant;
import com.flowpay.backend.payment.application.PaymentIntentRepository;
import com.flowpay.backend.payment.application.PaymentTransactionRepository;
import com.flowpay.backend.payment.domain.PaymentIntent;
import com.flowpay.backend.payment.domain.PaymentStatus;
import com.flowpay.backend.payment.domain.PaymentTransaction;
import com.flowpay.backend.testing.PostgresIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PaymentQueryApiTest extends PostgresIntegrationTest {

    private static final Instant BASE_TIME = Instant.parse("2026-08-29T10:00:00Z");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private MerchantRepository merchantRepository;

    @Autowired
    private ApiKeyRepository apiKeyRepository;

    @Autowired
    private ApiKeySecretCodec secretCodec;

    @Autowired
    private PaymentIntentRepository paymentIntentRepository;

    @Autowired
    private PaymentTransactionRepository paymentTransactionRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanData() {
        jdbcTemplate.update("""
                TRUNCATE TABLE payment_transactions, payment_intents, refresh_tokens,
                    merchant_api_keys, merchant_members, merchants, users
                    RESTART IDENTITY CASCADE
                """);
    }

    @Test
    void shouldReturnOwnDetailWithoutInternalFields() throws Exception {
        StoredKey owner = createStoredKey("mrc_read_owner", "key_read_owner");
        PaymentIntent payment = savePayment(
                "pi_read_detail",
                owner.merchantId(),
                "ORDER-DETAIL",
                PaymentStatus.SUCCEEDED,
                BASE_TIME
        );

        mockMvc.perform(get("/api/v1/payment-intents/{paymentId}", payment.publicId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(owner.rawKey())))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.data.id").value("pi_read_detail"))
                .andExpect(jsonPath("$.data.orderId").value("ORDER-DETAIL"))
                .andExpect(jsonPath("$.data.amount").value(10_000))
                .andExpect(jsonPath("$.data.currency").value("USD"))
                .andExpect(jsonPath("$.data.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.data.refundedAmount").value(0))
                .andExpect(jsonPath("$.data.refundableAmount").value(10_000))
                .andExpect(content().string(not(containsString("internalId"))))
                .andExpect(content().string(not(containsString("merchantId"))))
                .andExpect(content().string(not(containsString("\"version\""))))
                .andExpect(content().string(not(containsString("refundReservedAmount"))))
                .andExpect(content().string(not(containsString(owner.rawKey()))))
                .andExpect(content().string(not(containsString(owner.keyHash()))))
                .andExpect(content().string(not(containsString("providerPayload"))));
    }

    @Test
    void shouldReturnSameNotFoundForMissingAndCrossMerchantDetail() throws Exception {
        StoredKey owner = createStoredKey("mrc_read_requesting", "key_read_requesting");
        StoredKey other = createStoredKey("mrc_read_other", "key_read_other");
        PaymentIntent otherPayment = savePayment(
                "pi_other_merchant",
                other.merchantId(),
                "ORDER-OTHER",
                PaymentStatus.CREATED,
                BASE_TIME
        );

        assertPaymentNotFound(owner.rawKey(), otherPayment.publicId());
        assertPaymentNotFound(owner.rawKey(), "pi_missing");
    }

    @Test
    void shouldFilterPaginateAndSortOnlyOwningMerchantPayments() throws Exception {
        StoredKey owner = createStoredKey("mrc_list_owner", "key_list_owner");
        StoredKey other = createStoredKey("mrc_list_other", "key_list_other");
        savePayment("pi_oldest", owner.merchantId(), "ORDER-A", PaymentStatus.CREATED, BASE_TIME);
        savePayment("pi_succeeded", owner.merchantId(), "ORDER-B", PaymentStatus.SUCCEEDED, BASE_TIME.plusSeconds(10));
        savePayment("pi_order_match", owner.merchantId(), "ORDER-MATCH", PaymentStatus.CREATED, BASE_TIME.plusSeconds(20));
        savePayment("pi_newest", owner.merchantId(), "ORDER-D", PaymentStatus.FAILED, BASE_TIME.plusSeconds(30));
        savePayment("pi_other_hidden", other.merchantId(), "ORDER-MATCH", PaymentStatus.CREATED, BASE_TIME.plusSeconds(40));

        mockMvc.perform(get("/api/v1/payment-intents")
                        .header(HttpHeaders.AUTHORIZATION, bearer(owner.rawKey())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(4))
                .andExpect(jsonPath("$.data[0].id").value("pi_newest"))
                .andExpect(jsonPath("$.data[1].id").value("pi_order_match"))
                .andExpect(jsonPath("$.meta.page").value(0))
                .andExpect(jsonPath("$.meta.size").value(20))
                .andExpect(jsonPath("$.meta.totalElements").value(4));

        mockMvc.perform(get("/api/v1/payment-intents")
                        .header(HttpHeaders.AUTHORIZATION, bearer(owner.rawKey()))
                        .param("status", "SUCCEEDED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].id").value("pi_succeeded"));

        mockMvc.perform(get("/api/v1/payment-intents")
                        .header(HttpHeaders.AUTHORIZATION, bearer(owner.rawKey()))
                        .param("orderId", " ORDER-MATCH "))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].id").value("pi_order_match"));

        mockMvc.perform(get("/api/v1/payment-intents")
                        .header(HttpHeaders.AUTHORIZATION, bearer(owner.rawKey()))
                        .param("createdFrom", BASE_TIME.plusSeconds(10).toString())
                        .param("createdTo", BASE_TIME.plusSeconds(20).toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].id").value("pi_order_match"))
                .andExpect(jsonPath("$.data[1].id").value("pi_succeeded"));

        mockMvc.perform(get("/api/v1/payment-intents")
                        .header(HttpHeaders.AUTHORIZATION, bearer(owner.rawKey()))
                        .param("page", "1")
                        .param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value("pi_succeeded"))
                .andExpect(jsonPath("$.data[1].id").value("pi_oldest"))
                .andExpect(jsonPath("$.meta.totalPages").value(2))
                .andExpect(jsonPath("$.meta.hasPrevious").value(true))
                .andExpect(jsonPath("$.meta.hasNext").value(false));

        mockMvc.perform(get("/api/v1/payment-intents")
                        .header(HttpHeaders.AUTHORIZATION, bearer(owner.rawKey()))
                        .param("size", "101"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        mockMvc.perform(get("/api/v1/payment-intents")
                        .header(HttpHeaders.AUTHORIZATION, bearer(owner.rawKey()))
                        .param("status", "NOT_A_STATUS"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void shouldReturnSafeTransactionHistoryForOwnedPayment() throws Exception {
        StoredKey owner = createStoredKey("mrc_tx_owner", "key_tx_owner");
        PaymentIntent payment = savePayment(
                "pi_tx_history",
                owner.merchantId(),
                "ORDER-TX",
                PaymentStatus.SUCCEEDED,
                BASE_TIME
        );
        PaymentTransaction first = PaymentTransaction.createProcessing(
                "ptxn_first",
                payment.internalId(),
                1,
                "SIMULATOR",
                BASE_TIME.plusSeconds(1)
        );
        first.markSucceeded("provider-reference-1", BASE_TIME.plusSeconds(2));
        paymentTransactionRepository.save(first);
        PaymentTransaction second = PaymentTransaction.createProcessing(
                "ptxn_second",
                payment.internalId(),
                2,
                "SIMULATOR",
                BASE_TIME.plusSeconds(3)
        );
        second.markFailed(
                "provider-reference-2",
                "CARD_DECLINED",
                "The payment was declined.",
                BASE_TIME.plusSeconds(4)
        );
        paymentTransactionRepository.save(second);

        mockMvc.perform(get("/api/v1/payment-intents/{paymentId}/transactions", payment.publicId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(owner.rawKey())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].id").value("ptxn_first"))
                .andExpect(jsonPath("$.data[0].attemptNo").value(1))
                .andExpect(jsonPath("$.data[0].providerTransactionId").value("provider-reference-1"))
                .andExpect(jsonPath("$.data[1].id").value("ptxn_second"))
                .andExpect(jsonPath("$.data[1].failureCode").value("CARD_DECLINED"))
                .andExpect(jsonPath("$.data[1].failureMessage").value("The payment was declined."))
                .andExpect(content().string(not(containsString("paymentIntentId"))))
                .andExpect(content().string(not(containsString("internalId"))))
                .andExpect(content().string(not(containsString("\"version\""))));
    }

    private void assertPaymentNotFound(String rawKey, String paymentId) throws Exception {
        mockMvc.perform(get("/api/v1/payment-intents/{paymentId}", paymentId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(rawKey)))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("PAYMENT_NOT_FOUND"));
    }

    private StoredKey createStoredKey(String merchantPublicId, String keyPublicId) {
        Merchant merchant = merchantRepository.save(Merchant.create(
                merchantPublicId,
                "Payment Read Store",
                BASE_TIME
        ));
        GeneratedApiKeySecret secret = secretCodec.generate();
        apiKeyRepository.save(ApiKey.create(
                keyPublicId,
                merchant.id(),
                "Payment read key",
                secret.prefix(),
                secret.digest(),
                null,
                BASE_TIME
        ));
        return new StoredKey(merchant.id(), secret.rawKey(), secret.digest());
    }

    private PaymentIntent savePayment(
            String publicId,
            long merchantId,
            String orderId,
            PaymentStatus status,
            Instant createdAt
    ) {
        PaymentIntent payment = PaymentIntent.create(
                publicId,
                merchantId,
                orderId,
                "Payment " + orderId,
                Money.of(10_000L, "USD"),
                createdAt
        );
        if (status != PaymentStatus.CREATED) {
            payment.startProcessing(createdAt.plusMillis(1));
        }
        if (status == PaymentStatus.SUCCEEDED) {
            payment.markSucceeded(createdAt.plusMillis(2));
        } else if (status == PaymentStatus.FAILED) {
            payment.markFailed(createdAt.plusMillis(2));
        }
        return paymentIntentRepository.save(payment);
    }

    private static String bearer(String rawKey) {
        return "Bearer " + rawKey;
    }

    private record StoredKey(long merchantId, String rawKey, String keyHash) {
    }
}
