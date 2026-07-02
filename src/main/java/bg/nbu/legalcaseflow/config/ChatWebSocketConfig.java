package bg.nbu.legalcaseflow.config;

import bg.nbu.legalcaseflow.websocket.ChatWebSocketHandler;
import bg.nbu.legalcaseflow.websocket.AppWebSocketHandler;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class ChatWebSocketConfig implements WebSocketConfigurer {

    private final ChatWebSocketHandler chatWebSocketHandler;
    private final AppWebSocketHandler appWebSocketHandler;

    public ChatWebSocketConfig(ChatWebSocketHandler chatWebSocketHandler, AppWebSocketHandler appWebSocketHandler) {
        this.chatWebSocketHandler = chatWebSocketHandler;
        this.appWebSocketHandler = appWebSocketHandler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(chatWebSocketHandler, "/ws/chat")
                .setAllowedOriginPatterns("*");
        registry.addHandler(appWebSocketHandler, "/ws/events")
                .setAllowedOriginPatterns("*");
    }
}
