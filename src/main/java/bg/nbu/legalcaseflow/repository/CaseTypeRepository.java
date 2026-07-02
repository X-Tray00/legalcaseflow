package bg.nbu.legalcaseflow.repository;

import bg.nbu.legalcaseflow.domain.CaseType;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CaseTypeRepository extends JpaRepository<CaseType, Long> {
}
