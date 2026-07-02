package bg.nbu.legalcaseflow.repository;

import bg.nbu.legalcaseflow.domain.User;
import bg.nbu.legalcaseflow.domain.Role;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByUsername(String username);

    boolean existsByUsername(String username);

    Optional<User> findByClient_Id(Long clientId);

    Optional<User> findByLawyer_Id(Long lawyerId);

    List<User> findByRoleOrderByUsernameAsc(Role role);
}
