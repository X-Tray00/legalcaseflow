package bg.nbu.legalcaseflow.web.dto.request;

import jakarta.validation.constraints.NotNull;

public record ChatConversationRequest(
        @NotNull Long counterpartUserId
) {
}
