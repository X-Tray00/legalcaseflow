package bg.nbu.legalcaseflow.repository;

import bg.nbu.legalcaseflow.domain.Client;
import bg.nbu.legalcaseflow.report.dto.LabeledCount;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface ClientRepository extends JpaRepository<Client, Long> {

    boolean existsByIdentifier(String identifier);

    /** Report: clients assigned to a given lead lawyer (= пациенти към даден личен лекар). */
    List<Client> findByLeadLawyerIdOrderByFullNameAsc(Long lawyerId);

    /** Report: number of clients per lead lawyer. */
    @Query("select new bg.nbu.legalcaseflow.report.dto.LabeledCount(c.leadLawyer.fullName, count(c)) "
            + "from Client c where c.leadLawyer is not null "
            + "group by c.leadLawyer.fullName order by count(c) desc")
    List<LabeledCount> countByLeadLawyer();
}
