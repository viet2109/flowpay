package com.flowpay.backend.payment.infrastructure.persistence;

import com.flowpay.backend.payment.application.PaymentIntentRepository;
import com.flowpay.backend.payment.application.PaymentIntentPage;
import com.flowpay.backend.payment.application.PaymentIntentSearchCriteria;
import com.flowpay.backend.payment.domain.PaymentIntent;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class PaymentIntentRepositoryAdapter implements PaymentIntentRepository {

    private final PaymentIntentJpaRepository repository;

    @Override
    public PaymentIntent save(PaymentIntent paymentIntent) {
        PaymentIntentEntity saved = repository.saveAndFlush(
                PaymentIntentPersistenceMapper.toEntity(paymentIntent)
        );
        return PaymentIntentPersistenceMapper.toDomain(saved);
    }

    @Override
    public Optional<PaymentIntent> findByPublicIdAndMerchantId(String publicId, long merchantId) {
        return repository.findByPublicIdAndMerchantId(publicId, merchantId)
                .map(PaymentIntentPersistenceMapper::toDomain);
    }

    @Override
    public PaymentIntentPage search(PaymentIntentSearchCriteria criteria) {
        PageRequest pageRequest = PageRequest.of(
                criteria.page(),
                criteria.size(),
                Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"))
        );
        Page<PaymentIntentEntity> result = repository.findAll(specification(criteria), pageRequest);
        return new PaymentIntentPage(
                result.getContent().stream()
                        .map(PaymentIntentPersistenceMapper::toDomain)
                        .toList(),
                result.getNumber(),
                result.getSize(),
                result.getTotalElements(),
                result.getTotalPages(),
                result.hasNext(),
                result.hasPrevious()
        );
    }

    @Override
    public List<PaymentIntent> searchByMerchant(long merchantId) {
        return repository.findAllByMerchantIdOrderByCreatedAtDescIdDesc(merchantId).stream()
                .map(PaymentIntentPersistenceMapper::toDomain)
                .toList();
    }

    private static Specification<PaymentIntentEntity> specification(
            PaymentIntentSearchCriteria criteria
    ) {
        return (root, query, builder) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(builder.equal(root.get("merchantId"), criteria.merchantId()));
            if (criteria.status() != null) {
                predicates.add(builder.equal(root.get("status"), criteria.status()));
            }
            if (criteria.orderId() != null) {
                predicates.add(builder.equal(root.get("merchantOrderId"), criteria.orderId()));
            }
            if (criteria.createdFrom() != null) {
                predicates.add(builder.greaterThanOrEqualTo(
                        root.get("createdAt"),
                        criteria.createdFrom()
                ));
            }
            if (criteria.createdTo() != null) {
                predicates.add(builder.lessThanOrEqualTo(
                        root.get("createdAt"),
                        criteria.createdTo()
                ));
            }
            return builder.and(predicates.toArray(Predicate[]::new));
        };
    }
}
