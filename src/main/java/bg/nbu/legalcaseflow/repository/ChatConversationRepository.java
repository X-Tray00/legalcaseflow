package bg.nbu.legalcaseflow.repository;

import bg.nbu.legalcaseflow.domain.ChatConversation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ChatConversationRepository extends JpaRepository<ChatConversation, Long> {

    Optional<ChatConversation> findByClient_IdAndLawyer_Id(Long clientId, Long lawyerId);

    Optional<ChatConversation> findByAdmin_IdAndClient_Id(Long adminId, Long clientId);

    Optional<ChatConversation> findByAdmin_IdAndLawyer_Id(Long adminId, Long lawyerId);

    List<ChatConversation> findByClient_IdOrderByLastActivityAtDesc(Long clientId);

    List<ChatConversation> findByLawyer_IdOrderByLastActivityAtDesc(Long lawyerId);

    List<ChatConversation> findByAdmin_IdOrderByLastActivityAtDesc(Long adminId);
}
