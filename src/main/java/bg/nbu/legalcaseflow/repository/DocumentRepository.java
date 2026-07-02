package bg.nbu.legalcaseflow.repository;

import bg.nbu.legalcaseflow.domain.Document;
import bg.nbu.legalcaseflow.report.dto.LabeledCount;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface DocumentRepository extends JpaRepository<Document, Long> {

    List<Document> findByClientId(Long clientId);

    /** Report: number of issued documents per lawyer (first row = most). */
    @Query("select new bg.nbu.legalcaseflow.report.dto.LabeledCount(d.lawyer.fullName, count(d)) "
            + "from Document d group by d.lawyer.fullName order by count(d) desc")
    List<LabeledCount> countByLawyer();
}
