package bg.nbu.legalcaseflow.repository;

import bg.nbu.legalcaseflow.domain.Lawyer;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface LawyerRepository extends JpaRepository<Lawyer, Long> {

    Optional<Lawyer> findByRegistrationNumber(String registrationNumber);

    boolean existsByRegistrationNumber(String registrationNumber);
}
