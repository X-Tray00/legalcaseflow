package bg.nbu.legalcaseflow.websocket;

import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
public class ChatEventPublisher {

    private final ChatWebSocketHandler webSocketHandler;

    public ChatEventPublisher(ChatWebSocketHandler webSocketHandler) {
        this.webSocketHandler = webSocketHandler;
    }

    public void publishAfterCommit(String username, ChatWebSocketEvent event) {
        // COURSEWORK: real-time събитие се изпраща след DB commit. При rollback браузърът
        // не получава "фантомно" съобщение, което всъщност не е било записано.
        Runnable publish = () -> webSocketHandler.sendToUser(username, event);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    publish.run();
                }
            });
        } else {
            publish.run();
        }
    }
}
