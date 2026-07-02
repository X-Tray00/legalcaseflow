package bg.nbu.legalcaseflow.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.SQLRestriction;

import java.time.Instant;

/** Category of legal matter / вид казус (analog of "Диагноза"). */
@Entity
@Table(name = "case_types")
@SQLRestriction("deleted_at is null")
@Getter
@Setter
@NoArgsConstructor
public class CaseType {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String name;

    private String description;

    private Instant deletedAt;
}
