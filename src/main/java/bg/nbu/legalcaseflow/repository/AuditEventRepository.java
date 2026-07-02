package bg.nbu.legalcaseflow.repository;

import bg.nbu.legalcaseflow.domain.AuditEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.Optional;

public interface AuditEventRepository extends JpaRepository<AuditEvent, Long>, JpaSpecificationExecutor<AuditEvent> {

    boolean existsBySourceAuditEventId(Long sourceAuditEventId);

    Optional<AuditEvent> findTopByResourceTypeAndResourceIdOrderByIdDesc(String resourceType, Long resourceId);
}
