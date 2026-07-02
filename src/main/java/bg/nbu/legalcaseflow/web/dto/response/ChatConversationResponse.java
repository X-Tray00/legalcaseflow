package bg.nbu.legalcaseflow.web.dto.response;

import java.time.Instant;

public record ChatConversationResponse(
        Long id,
        Long counterpartUserId,
        String counterpartName,
        String counterpartRole,
        Instant createdAt,
        Instant lastActivityAt,
        ChatMessageResponse lastMessage,
        long unreadCount
) {
}
