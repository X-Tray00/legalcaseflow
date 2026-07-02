package bg.nbu.legalcaseflow.repository;

import bg.nbu.legalcaseflow.domain.Client;
import bg.nbu.legalcaseflow.domain.LegalService;
import bg.nbu.legalcaseflow.domain.Payer;
import bg.nbu.legalcaseflow.report.dto.LabeledAmount;
import bg.nbu.legalcaseflow.report.dto.LabeledCount;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public interface LegalServiceRepository extends JpaRepository<LegalService, Long> {

    List<LegalService> findByClientId(Long clientId);

    List<LegalService> findByClientIdOrderByDateDesc(Long clientId);

    List<LegalService> findByLawyerIdOrderByDateDesc(Long lawyerId);

    List<LegalService> findAllByOrderByDateDesc();

    List<LegalService> findByLawyerIdAndDateBetweenOrderByDateDesc(Long lawyerId, LocalDate from, LocalDate to);

    List<LegalService> findByDateBetweenOrderByDateDesc(LocalDate from, LocalDate to);

    /** Report: clients that have a service of a given case type. */
    @Query("select distinct s.client from LegalService s where s.caseType.id = :caseTypeId")
    List<Client> findClientsByCaseType(Long caseTypeId);

    /** Report: count of services grouped by case type (first row = most common). */
    // COURSEWORK: constructor expression връща DTO projection директно от JPQL и не зарежда
    // цели entity графи само за агрегирана справка.
    @Query("select new bg.nbu.legalcaseflow.report.dto.LabeledCount(s.caseType.name, count(s)) "
            + "from LegalService s group by s.caseType.name order by count(s) desc")
    List<LabeledCount> countByCaseType();

    /** Report: total amount paid by a given payer (e.g. CLIENT). */
    @Query("select sum(s.fee) from LegalService s where s.payer = :payer and s.paid = true")
    BigDecimal totalByPayerPaid(Payer payer);

    /** Report: paid revenue per lawyer for a given payer. */
    @Query("select new bg.nbu.legalcaseflow.report.dto.LabeledAmount(s.lawyer.fullName, sum(s.fee)) "
            + "from LegalService s where s.payer = :payer and s.paid = true "
            + "group by s.lawyer.fullName order by sum(s.fee) desc")
    List<LabeledAmount> revenuePerLawyer(Payer payer);

    /** Report: number of services per lawyer. */
    @Query("select new bg.nbu.legalcaseflow.report.dto.LabeledCount(s.lawyer.fullName, count(s)) "
            + "from LegalService s group by s.lawyer.fullName order by count(s) desc")
    List<LabeledCount> serviceCountPerLawyer();
}
