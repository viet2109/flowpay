package com.flowpay.backend.payment.infrastructure.persistence;

import com.flowpay.backend.payment.domain.PaymentStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

@Entity(name = "PaymentIntentEntity")
@Table(name = "payment_intents")
@Getter(AccessLevel.PACKAGE)
@Accessors(fluent = true)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PACKAGE)
class PaymentIntentEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "public_id", nullable = false, unique = true, length = 64)
    private String publicId;

    @Column(name = "merchant_id", nullable = false)
    private long merchantId;

    @Column(name = "merchant_order_id")
    private String merchantOrderId;

    @Column
    private String description;

    @Column(name = "amount_minor", nullable = false)
    private long amountMinor;

    @Column(nullable = false, length = 3, columnDefinition = "CHAR(3)")
    @JdbcTypeCode(SqlTypes.CHAR)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private PaymentStatus status;

    @Column(name = "refunded_amount_minor", nullable = false)
    private long refundedAmountMinor;

    @Column(name = "refund_reserved_minor", nullable = false)
    private long refundReservedMinor;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(nullable = false)
    private long version;
}
