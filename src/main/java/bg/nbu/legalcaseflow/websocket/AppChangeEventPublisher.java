package bg.nbu.legalcaseflow.websocket;

import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
public class AppChangeEventPublisher {

    private final AppWebSocketHandler webSocketHandler;

    public AppChangeEventPublisher(AppWebSocketHandler webSocketHandler) {
        this.webSocketHandler = webSocketHandler;
    }

    public void publish(String resource, String action) {
        webSocketHandler.broadcast(new AppWebSocketEvent(
                "RESOURCE_CHANGED",
                resource,
                action,
                Instant.now()
        ));
    }

    public void publishToAdmins(String resource, String action) {
        webSocketHandler.broadcastToRole(new AppWebSocketEvent(
                "RESOURCE_CHANGED",
                resource,
                action,
                Instant.now()
        ), "ADMIN");
    }
}
