package bg.nbu.legalcaseflow.web.controller;

import bg.nbu.legalcaseflow.domain.User;
import bg.nbu.legalcaseflow.domain.AuditAction;
import bg.nbu.legalcaseflow.domain.AuditOutcome;
import bg.nbu.legalcaseflow.exception.NotFoundException;
import bg.nbu.legalcaseflow.repository.UserRepository;
import bg.nbu.legalcaseflow.security.JwtService;
import bg.nbu.legalcaseflow.service.AuditService;
import bg.nbu.legalcaseflow.web.dto.response.AuthResponse;
import bg.nbu.legalcaseflow.web.dto.request.LoginRequest;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
@Tag(name = "Authentication", description = "Login and JWT issuance")
public class AuthController {

    private final UserRepository userRepository;
    private final AuthenticationManager authenticationManager;
    private final JwtService jwtService;
    private final AuditService auditService;

    public AuthController(UserRepository userRepository,
                          AuthenticationManager authenticationManager,
                          JwtService jwtService,
                          AuditService auditService) {
        this.userRepository = userRepository;
        this.authenticationManager = authenticationManager;
        this.jwtService = jwtService;
        this.auditService = auditService;
    }

    @PostMapping("/login")
    @Transactional(readOnly = true)
    public AuthResponse login(@Valid @RequestBody LoginRequest request) {
        try {
            authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(request.username(), request.password()));
        } catch (AuthenticationException ex) {
            auditService.recordSecurity(AuditAction.LOGIN_FAILURE, AuditOutcome.FAILURE, request.username(),
                    java.util.Map.of("reason", "Invalid credentials"));
            throw ex;
        }
        User user = userRepository.findByUsername(request.username())
                .orElseThrow(() -> new NotFoundException("User not found: " + request.username()));
        String token = jwtService.generateToken(user.getUsername(), user.getRole().name());
        // COURSEWORK: profile ID-тата позволяват на SPA клиента да заключи relation полета към
        // текущия адвокат/доверител; backend проверките остават истинската граница за сигурност.
        Long lawyerId = user.getLawyer() == null ? null : user.getLawyer().getId();
        Long clientId = user.getClient() == null ? null : user.getClient().getId();
        auditService.recordSecurity(AuditAction.LOGIN_SUCCESS, AuditOutcome.SUCCESS, user.getUsername(),
                java.util.Map.of("role", user.getRole()));
        return new AuthResponse(token, user.getUsername(), user.getRole().name(), lawyerId, clientId);
    }
}
