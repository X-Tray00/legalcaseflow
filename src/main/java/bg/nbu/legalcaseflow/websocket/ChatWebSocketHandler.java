package bg.nbu.legalcaseflow.websocket;

import bg.nbu.legalcaseflow.domain.Role;
import bg.nbu.legalcaseflow.domain.User;
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
import java.util.concurrent.ConcurrentHashMap;

@Component
public class ChatWebSocketHandler extends TextWebSocketHandler {

    private final JwtService jwtService;
    private final UserRepository userRepository;
    private final ObjectMapper objectMapper;
    private final ConcurrentHashMap<String, Set<WebSocketSession>> sessionsByUsername = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> usernameBySessionId = new ConcurrentHashMap<>();

    public ChatWebSocketHandler(JwtService jwtService, UserRepository userRepository, ObjectMapper objectMapper) {
        this.jwtService = jwtService;
        this.userRepository = userRepository;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        if (usernameBySessionId.containsKey(session.getId())) {
            closePolicyViolation(session, "WebSocket accepts AUTH only; use the REST API to send messages");
            return;
        }

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

            String username = jwtService.extractUsername(token);
            User user = userRepository.findByUsername(username).orElse(null);
            if (user == null || !user.isActive() || !hasChatIdentity(user)) {
                closePolicyViolation(session, "Chat access denied");
                return;
            }

            usernameBySessionId.put(session.getId(), username);
            sessionsByUsername.computeIfAbsent(username, ignored -> ConcurrentHashMap.newKeySet()).add(session);
            send(session, new ChatWebSocketEvent("AUTHENTICATED", null, null));
        } catch (Exception ex) {
            closePolicyViolation(session, "Invalid AUTH message");
        }
    }

    public void sendToUser(String username, ChatWebSocketEvent event) {
        Set<WebSocketSession> sessions = sessionsByUsername.get(username);
        if (sessions == null) {
            return;
        }
        for (WebSocketSession session : Set.copyOf(sessions)) {
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

    private boolean hasChatIdentity(User user) {
        return user.getRole() == Role.ADMIN
                || (user.getRole() == Role.CLIENT && user.getClient() != null)
                || (user.getRole() == Role.LAWYER && user.getLawyer() != null);
    }

    private void send(WebSocketSession session, ChatWebSocketEvent event) throws IOException {
        synchronized (session) {
            session.sendMessage(new TextMessage(objectMapper.writeValueAsString(event)));
        }
    }

    private void closePolicyViolation(WebSocketSession session, String reason) throws IOException {
        session.close(new CloseStatus(CloseStatus.POLICY_VIOLATION.getCode(), reason));
    }

    private void unregister(WebSocketSession session) {
        String username = usernameBySessionId.remove(session.getId());
        if (username == null) {
            return;
        }
        Set<WebSocketSession> sessions = sessionsByUsername.get(username);
        if (sessions != null) {
            sessions.remove(session);
            if (sessions.isEmpty()) {
                sessionsByUsername.remove(username, sessions);
            }
        }
    }
}
