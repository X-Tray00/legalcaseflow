package bg.nbu.legalcaseflow.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
// COURSEWORK: unique constraints гарантират на ниво БД, че допустима двойка има само един разговор.
// Service слоят прави POST idempotent, а базата пази инвариантa и при конкурентни заявки.
@Table(name = "chat_conversations",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_chat_client_lawyer", columnNames = {"client_id", "lawyer_id"}),
                @UniqueConstraint(name = "uk_chat_admin_client", columnNames = {"admin_id", "client_id"}),
                @UniqueConstraint(name = "uk_chat_admin_lawyer", columnNames = {"admin_id", "lawyer_id"})
        })
@Getter
@Setter
@NoArgsConstructor
public class ChatConversation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "client_id")
    private Client client;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "lawyer_id")
    private Lawyer lawyer;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "admin_id")
    private User admin;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant lastActivityAt;
}
