package com.flowpay.backend.merchant.infrastructure.persistence;

import com.flowpay.backend.merchant.application.MerchantRepository;
import com.flowpay.backend.merchant.domain.Merchant;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public class JpaMerchantRepositoryAdapter implements MerchantRepository {

    private final SpringDataMerchantRepository repository;

    public JpaMerchantRepositoryAdapter(SpringDataMerchantRepository repository) {
        this.repository = repository;
    }

    @Override
    public Merchant save(Merchant merchant) {
        MerchantEntity saved = repository.saveAndFlush(toEntity(merchant));
        return toDomain(saved);
    }

    @Override
    public Optional<Merchant> findById(long id) {
        return repository.findById(id).map(JpaMerchantRepositoryAdapter::toDomain);
    }

    @Override
    public Optional<Merchant> findByPublicId(String publicId) {
        return repository.findByPublicId(publicId).map(JpaMerchantRepositoryAdapter::toDomain);
    }

    private static MerchantEntity toEntity(Merchant merchant) {
        return new MerchantEntity(
                merchant.id(),
                merchant.publicId(),
                merchant.name(),
                merchant.status(),
                merchant.createdAt(),
                merchant.updatedAt(),
                merchant.version()
        );
    }

    private static Merchant toDomain(MerchantEntity entity) {
        return Merchant.rehydrate(
                entity.id(),
                entity.publicId(),
                entity.name(),
                entity.status(),
                entity.version(),
                entity.createdAt(),
                entity.updatedAt()
        );
    }
}
