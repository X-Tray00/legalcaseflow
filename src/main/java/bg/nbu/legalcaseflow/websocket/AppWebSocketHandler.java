package bg.nbu.legalcaseflow.websocket;

import bg.nbu.legalcaseflow.repository.UserRepository;
import bg.nbu.legalcaseflow.security.JwtService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.Set;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class AppWebSocketHandler extends TextWebSocketHandler {

    private final JwtService jwtService;
    private final UserRepository userRepository;
    private final ObjectMapper objectMapper;
    private final Set<WebSocketSession> authenticatedSessions = ConcurrentHashMap.newKeySet();
    private final Set<String> authenticatedSessionIds = ConcurrentHashMap.newKeySet();
    private final Map<String, String> sessionRoles = new ConcurrentHashMap<>();

    // този клас се използва за да се обработи текстовото съобщение от клиента.
    public AppWebSocketHandler(JwtService jwtService, UserRepository userRepository, ObjectMapper objectMapper) {
        this.jwtService = jwtService;
        this.userRepository = userRepository;
        this.objectMapper = objectMapper;
    }

    // този метод се използва за да се обработи текстовото съобщение от клиента.
    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        if (authenticatedSessionIds.contains(session.getId())) {
            closePolicyViolation(session, "WebSocket accepts AUTH only");
            return;
        }

        // проверяваме дали се е изпратил валиден JWT token.
        try {
            JsonNode payload = objectMapper.readTree(message.getPayload());
            if (!"AUTH".equals(payload.path("type").asText()) || payload.path("token").asText().isBlank()) {
                closePolicyViolation(session, "AUTH must be the first WebSocket message");
                return;
            }

            String token = payload.path("token").asText();
            if (!jwtService.isValid(token)) {
                closePolicyViolation(session, "Invalid token");
                return;
            }

            // извличаме username от JWT token.
            String username = jwtService.extractUsername(token);
            // проверяваме дали този акаунт съществува.
            var user = userRepository.findByUsername(username).orElse(null);
            // проверяваме дали този акаунт е активен.
            if (user == null || !user.isActive()) {
                closePolicyViolation(session, "User not found");
                return;
            }

            authenticatedSessionIds.add(session.getId());
            authenticatedSessions.add(session);
            sessionRoles.put(session.getId(), user.getRole().name());
            send(session, new AppWebSocketEvent("AUTHENTICATED", null, null, null));
        } catch (Exception ex) {
            closePolicyViolation(session, "Invalid AUTH message");
        }
    }

    public void broadcast(AppWebSocketEvent event) {
        broadcast(event, null);
    }

    public void broadcastToRole(AppWebSocketEvent event, String role) {
        broadcast(event, role);
    }

    // този метод се използва за да се изпрати съобщение на всички автентикирани клиенти.
    private void broadcast(AppWebSocketEvent event, String role) {
        for (WebSocketSession session : Set.copyOf(authenticatedSessions)) {
            if (role != null && !role.equals(sessionRoles.get(session.getId()))) {
                continue;
            }
            if (!session.isOpen()) {
                unregister(session);
                continue;
            }
            try {
                send(session, event);
            } catch (IOException ex) {
                unregister(session);
            }
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        unregister(session);
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
        unregister(session);
        if (session.isOpen()) {
            session.close(CloseStatus.SERVER_ERROR);
        }
    }

    private void send(WebSocketSession session, AppWebSocketEvent event) throws IOException {
        synchronized (session) {
            session.sendMessage(new TextMessage(objectMapper.writeValueAsString(event)));
        }
    }

    private void closePolicyViolation(WebSocketSession session, String reason) throws IOException {
        session.close(new CloseStatus(CloseStatus.POLICY_VIOLATION.getCode(), reason));
    }

    private void unregister(WebSocketSession session) {
        authenticatedSessionIds.remove(session.getId());
        authenticatedSessions.remove(session);
        sessionRoles.remove(session.getId());
    }
}
