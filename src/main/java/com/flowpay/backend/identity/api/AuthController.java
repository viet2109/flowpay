package com.flowpay.backend.identity.api;

import com.flowpay.backend.common.api.ApiResponse;
import com.flowpay.backend.identity.application.RegistrationResult;
import com.flowpay.backend.identity.application.RegistrationUseCase;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final RegistrationUseCase registrationUseCase;
    private final RegistrationApiMapper mapper;

    public AuthController(RegistrationUseCase registrationUseCase, RegistrationApiMapper mapper) {
        this.registrationUseCase = registrationUseCase;
        this.mapper = mapper;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<RegisterResponse> register(@Valid @RequestBody RegisterRequest request) {
        RegistrationResult result = registrationUseCase.register(mapper.toCommand(request));
        return ApiResponse.of(mapper.toResponse(result));
    }
}
