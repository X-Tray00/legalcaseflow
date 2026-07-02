package bg.nbu.legalcaseflow.web.dto.response;

import java.time.Instant;

public record ChatMessageResponse(
        Long id,
        Long conversationId,
        Long senderUserId,
        String senderName,
        String content,
        Instant sentAt,
        Instant readAt,
        boolean own
) {
}
