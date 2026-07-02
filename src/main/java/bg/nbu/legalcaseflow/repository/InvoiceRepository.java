package bg.nbu.legalcaseflow.repository;

import bg.nbu.legalcaseflow.domain.Invoice;
import bg.nbu.legalcaseflow.domain.InvoiceStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface InvoiceRepository extends JpaRepository<Invoice, Long> {

    boolean existsByInvoiceNumber(String invoiceNumber);

    boolean existsByLegalService_IdAndStatus(Long legalServiceId, InvoiceStatus status);

    List<Invoice> findAllByOrderByIssueDateDesc();

    List<Invoice> findByLegalServiceClientIdOrderByIssueDateDesc(Long clientId);

    List<Invoice> findByLegalServiceLawyerIdOrderByIssueDateDesc(Long lawyerId);
}
