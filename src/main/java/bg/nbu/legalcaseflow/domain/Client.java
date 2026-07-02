package bg.nbu.legalcaseflow.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.SQLRestriction;

import java.time.Instant;

/** A client / доверител (analog of "Пациент"). */
@Entity
@Table(name = "clients")
@SQLRestriction("deleted_at is null")
@Getter
@Setter
@NoArgsConstructor
public class Client {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String fullName;

    /** ЕГН / identifier. */
    @Column(unique = true)
    private String identifier;

    private String contact;

    /**
     * Whether the client qualifies for state legal aid (НБПП).
     * Drives the conditional payer logic — analog of "здравноосигурителен статус".
     */
    @Column(nullable = false)
    private boolean legalAidEligible;

    /** Lead/assigned lawyer — analog of "личен лекар". */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "lead_lawyer_id")
    private Lawyer leadLawyer;

    private Instant deletedAt;
}
