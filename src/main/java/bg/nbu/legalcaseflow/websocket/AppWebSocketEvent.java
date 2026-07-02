package bg.nbu.legalcaseflow.websocket;

import java.time.Instant;

public record AppWebSocketEvent(
        String type,
        String resource,
        String action,
        Instant changedAt
) {
}
