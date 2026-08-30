package com.flowpay.backend.identity.api;

import com.flowpay.backend.common.api.ApiResponse;
import com.flowpay.backend.identity.application.AuthSessionUseCase;
import com.flowpay.backend.identity.application.LoginCommand;
import com.flowpay.backend.identity.application.LoginResult;
import com.flowpay.backend.identity.application.RefreshResult;
import com.flowpay.backend.identity.application.RegistrationResult;
import com.flowpay.backend.identity.application.RegistrationUseCase;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final RegistrationUseCase registrationUseCase;
    private final AuthSessionUseCase authSessionUseCase;
    private final RegistrationApiMapper mapper;
    private final RefreshCookieFactory cookieFactory;

    public AuthController(
            RegistrationUseCase registrationUseCase,
            AuthSessionUseCase authSessionUseCase,
            RegistrationApiMapper mapper,
            RefreshCookieFactory cookieFactory
    ) {
        this.registrationUseCase = registrationUseCase;
        this.authSessionUseCase = authSessionUseCase;
        this.mapper = mapper;
        this.cookieFactory = cookieFactory;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<RegisterResponse> register(@Valid @RequestBody RegisterRequest request) {
        RegistrationResult result = registrationUseCase.register(mapper.toCommand(request));
        return ApiResponse.of(mapper.toResponse(result));
    }

    @PostMapping("/login")
    public ResponseEntity<ApiResponse<LoginResponse>> login(@Valid @RequestBody LoginRequest request) {
        LoginResult result = authSessionUseCase.login(new LoginCommand(request.email(), request.password()));
        LoginResponse response = new LoginResponse(
                result.accessToken().value(),
                result.accessToken().expiresInSeconds(),
                new LoginResponse.UserView(result.user().publicId(), result.user().email())
        );
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cookieFactory.issue(result.refreshToken()).toString())
                .body(ApiResponse.of(response));
    }

    @PostMapping("/refresh")
    public ResponseEntity<ApiResponse<RefreshResponse>> refresh(
            @CookieValue(name = RefreshCookieFactory.COOKIE_NAME, required = false) String rawRefreshToken
    ) {
        RefreshResult result = authSessionUseCase.refresh(rawRefreshToken);
        RefreshResponse response = new RefreshResponse(
                result.accessToken().value(),
                result.accessToken().expiresInSeconds()
        );
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cookieFactory.issue(result.refreshToken()).toString())
                .body(ApiResponse.of(response));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(
            @CookieValue(name = RefreshCookieFactory.COOKIE_NAME, required = false) String rawRefreshToken
    ) {
        authSessionUseCase.logout(rawRefreshToken);
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, cookieFactory.clear().toString())
                .build();
    }
}
