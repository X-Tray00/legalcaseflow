package bg.nbu.legalcaseflow.service;

import bg.nbu.legalcaseflow.domain.Client;
import bg.nbu.legalcaseflow.domain.AuditAction;
import bg.nbu.legalcaseflow.domain.Lawyer;
import bg.nbu.legalcaseflow.domain.Role;
import bg.nbu.legalcaseflow.domain.User;
import bg.nbu.legalcaseflow.exception.ConflictException;
import bg.nbu.legalcaseflow.repository.UserRepository;
import bg.nbu.legalcaseflow.web.dto.request.AccountCredentialsRequest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Service for provisioning accounts for clients and lawyers.
 */
// този клас се използва за да се създаде нов акаунт за клиент или адвокат.
@Service
public class AccountProvisioningService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuditService auditService;

    public AccountProvisioningService(UserRepository userRepository, PasswordEncoder passwordEncoder,
                                      AuditService auditService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.auditService = auditService;
    }

    // този метод се използва за да се създаде нов акаунт за клиент.
    public void createForClient(AccountCredentialsRequest request, Client client, boolean canEditExisting) {
        if (request == null) {
            return;
        }
        // проверяваме дали този клиент вече има акаунт.
        User existing = userRepository.findByClient_Id(client.getId()).orElse(null);
        if (existing != null) {
            if (!canEditExisting) {
                throw new ConflictException("Client already has a login account");
            }
            updateCredentials(existing, request);
            return;
        }
        // ако няма акаунт, създаваме нов акаунт за клиента.
        User user = newUser(request, Role.CLIENT);
        user.setClient(client);
        userRepository.save(user);
        auditService.record(AuditAction.ACCOUNT_CREATED, "accounts", user.getId(), null,
                Map.of("username", user.getUsername(), "role", user.getRole(), "clientId", client.getId()), null);
    }

    // този метод се използва за да се създаде нов акаунт за адвокат.
    public void createForLawyer(AccountCredentialsRequest request, Lawyer lawyer, boolean canEditExisting) {
        if (request == null) {
            return;
        }
        // проверяваме дали този адвокат вече има акаунт.
        User existing = userRepository.findByLawyer_Id(lawyer.getId()).orElse(null);
        if (existing != null) {
            if (!canEditExisting) {
                throw new ConflictException("Lawyer already has a login account");
            }
            updateCredentials(existing, request);
            return;
        }
        // ако няма акаунт, създаваме нов акаунт за адвоката.
        User user = newUser(request, Role.LAWYER);
        user.setLawyer(lawyer);
        userRepository.save(user);
        auditService.record(AuditAction.ACCOUNT_CREATED, "accounts", user.getId(), null,
                Map.of("username", user.getUsername(), "role", user.getRole(), "lawyerId", lawyer.getId()), null);
    }

    /** Admin-only: change the username and reset the password of an existing login account. */
    private void updateCredentials(User user, AccountCredentialsRequest request) {
        String username = request.username().strip();
        validateUsername(username);
        if (!username.equals(user.getUsername()) && userRepository.existsByUsername(username)) {
            throw new IllegalArgumentException("Username already taken: " + username);
        }
        String previousUsername = user.getUsername();
        user.setUsername(username);
        user.setPassword(passwordEncoder.encode(request.password()));
        userRepository.save(user);
        auditService.record(AuditAction.ACCOUNT_UPDATED, "accounts", user.getId(),
                Map.of("username", previousUsername),
                Map.of("username", username, "role", user.getRole()), null);
    }

    public String usernameForClient(Long clientId) {
        return userRepository.findByClient_Id(clientId).filter(User::isActive).map(User::getUsername).orElse(null);
    }

    public String usernameForLawyer(Long lawyerId) {
        return userRepository.findByLawyer_Id(lawyerId).filter(User::isActive).map(User::getUsername).orElse(null);
    }

    // този метод се използва за да се създаде нов акаунт за клиент или адвокат.
    private User newUser(AccountCredentialsRequest request, Role role) {
        String username = request.username().strip();
        validateUsername(username);
        // проверяваме дали този акаунт вече съществува.
        if (userRepository.existsByUsername(username)) {
            throw new IllegalArgumentException("Username already taken: " + username);
        }

        User user = new User();
        user.setUsername(username);
        // PasswordEncoder е BCrypt bean; hash-ът е salted и не е обратимо криптиране.
        user.setPassword(passwordEncoder.encode(request.password()));
        user.setRole(role);
        return user;
    }

    private void validateUsername(String username) {
        if (username.length() < 3 || username.length() > 50) {
            throw new IllegalArgumentException("Username must be between 3 and 50 characters");
        }
    }
}
