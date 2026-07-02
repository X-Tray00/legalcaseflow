package bg.nbu.legalcaseflow.service;

import bg.nbu.legalcaseflow.domain.Role;
import bg.nbu.legalcaseflow.domain.User;
import bg.nbu.legalcaseflow.repository.UserRepository;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class CurrentUserService {

    private final UserRepository userRepository;

    public CurrentUserService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    public User currentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new AccessDeniedException("Authentication required");
        }
        return userRepository.findByUsername(authentication.getName())
                .orElseThrow(() -> new AccessDeniedException("Authenticated user is not registered"));
    }

    public boolean isAdmin(User user) {
        return user.getRole() == Role.ADMIN;
    }

    public boolean isLawyer(User user) {
        return user.getRole() == Role.LAWYER;
    }

    public boolean isClient(User user) {
        return user.getRole() == Role.CLIENT;
    }

    public Long lawyerId(User user) {
        if (user.getLawyer() == null) {
            throw new AccessDeniedException("Lawyer profile is required");
        }
        return user.getLawyer().getId();
    }

    public Long clientId(User user) {
        if (user.getClient() == null) {
            throw new AccessDeniedException("Client profile is required");
        }
        return user.getClient().getId();
    }

    public void requireAdminOrLawyer(User user) {
        if (!isAdmin(user) && !isLawyer(user)) {
            throw new AccessDeniedException("Admin or lawyer access is required");
        }
    }
}

