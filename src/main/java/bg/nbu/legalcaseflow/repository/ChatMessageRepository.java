package bg.nbu.legalcaseflow.repository;

import bg.nbu.legalcaseflow.domain.ChatMessage;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface ChatMessageRepository extends JpaRepository<ChatMessage, Long> {

    Optional<ChatMessage> findTopByConversation_IdOrderByIdDesc(Long conversationId);

    List<ChatMessage> findByConversation_IdOrderByIdDesc(Long conversationId, Pageable pageable);

    List<ChatMessage> findByConversation_IdAndIdLessThanOrderByIdDesc(Long conversationId, Long beforeId, Pageable pageable);

    long countByConversation_IdAndSender_IdNotAndReadAtIsNull(Long conversationId, Long userId);

    @Modifying(flushAutomatically = true)
    @Query("update ChatMessage m set m.readAt = :readAt "
            + "where m.conversation.id = :conversationId and m.sender.id <> :readerId and m.readAt is null")
    int markReceivedMessagesRead(Long conversationId, Long readerId, Instant readAt);
}
