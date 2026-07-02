package bg.nbu.legalcaseflow.websocket;

public record ChatWebSocketEvent(
        String type,
        Long conversationId,
        Object data
) {
}
