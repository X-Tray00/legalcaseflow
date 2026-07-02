package bg.nbu.legalcaseflow.web.dto.response;

import java.time.Instant;

public record ChatReadResponse(
        Long conversationId,
        Long readerUserId,
        Instant readAt
) {
}
