package bg.nbu.legalcaseflow.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.SQLRestriction;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.Instant;

/**
 * A rendered legal service / правна услуга (analog of "Преглед").
 * Fee is set by the lawyer; the payer is derived from the client's legal-aid status.
 */
@Entity
@Table(name = "legal_services")
@SQLRestriction("deleted_at is null")
@Getter
@Setter
@NoArgsConstructor
public class LegalService {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private LocalDate date;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "lawyer_id")
    private Lawyer lawyer;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "client_id")
    private Client client;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "case_type_id")
    private CaseType caseType;

    private String description;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal fee;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Payer payer;

    @Column(nullable = false)
    private boolean paid;

    private Instant deletedAt;
}
