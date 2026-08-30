package com.flowpay.backend.identity.application;

import com.flowpay.backend.common.error.ApiException;
import com.flowpay.backend.common.error.ErrorCode;
import com.flowpay.backend.identity.domain.Email;
import com.flowpay.backend.identity.domain.PasswordHash;
import com.flowpay.backend.identity.domain.User;
import com.flowpay.backend.merchant.application.MerchantOnboardingApi;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Service
@RequiredArgsConstructor
public class RegistrationService implements RegistrationUseCase {

    private static final String DUPLICATE_EMAIL_DETAIL = "A user with this email already exists.";

    private final UserRepository userRepository;
    private final PasswordHasher passwordHasher;
    private final UserPublicIdGenerator publicIdGenerator;
    private final MerchantOnboardingApi merchantOnboardingApi;

    @Override
    @Transactional
    public RegistrationResult register(RegistrationCommand command) {
        Email email = Email.of(command.email());
        if (userRepository.findByEmail(email).isPresent()) {
            throw duplicateEmail();
        }

        PasswordHash passwordHash = passwordHasher.hash(command.password());
        User user = User.create(
                publicIdGenerator.nextId(),
                email,
                passwordHash,
                command.firstName(),
                command.lastName(),
                Instant.now()
        );

        User savedUser;
        try {
            savedUser = userRepository.save(user);
        } catch (DataIntegrityViolationException exception) {
            throw duplicateEmail();
        }

        MerchantOnboardingApi.Result merchant = merchantOnboardingApi.onboard(
                new MerchantOnboardingApi.Command(savedUser.id(), command.merchantName())
        );

        return new RegistrationResult(
                new RegistrationResult.UserSnapshot(
                        savedUser.publicId(),
                        savedUser.email().value(),
                        savedUser.firstName(),
                        savedUser.lastName()
                ),
                new RegistrationResult.MerchantSnapshot(
                        merchant.merchantPublicId(),
                        merchant.merchantName(),
                        merchant.status().name()
                )
        );
    }

    private static ApiException duplicateEmail() {
        return new ApiException(
                HttpStatus.CONFLICT,
                ErrorCode.USER_EMAIL_ALREADY_EXISTS,
                DUPLICATE_EMAIL_DETAIL
        );
    }
}
