package bg.nbu.legalcaseflow.websocket;

import bg.nbu.legalcaseflow.security.JwtService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;

import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

@SpringBootTest
class ChatWebSocketHandlerTest {

    @Autowired ChatWebSocketHandler handler;
    @Autowired JwtService jwtService;

    @Test
    void authenticatesChatUsersAndTargetsEvents() throws Exception {
        WebSocketSession maria = session("maria-session");
        WebSocketSession ivanov = session("ivanov-session");

        handler.handleMessage(maria, auth("maria", "CLIENT"));
        handler.handleMessage(ivanov, auth("ivanov", "LAWYER"));
        clearInvocations(maria, ivanov);

        handler.sendToUser("maria", new ChatWebSocketEvent("MESSAGE_CREATED", 1L, "payload"));

        verify(maria).sendMessage(argThat(message -> message.getPayload().toString().contains("MESSAGE_CREATED")));
        verify(ivanov, never()).sendMessage(any(WebSocketMessage.class));
    }

    @Test
    void rejectsInvalidTokensAndAuthenticatesAdmins() throws Exception {
        WebSocketSession invalid = session("invalid-session");
        WebSocketSession admin = session("admin-session");

        handler.handleMessage(invalid, new TextMessage("{\"type\":\"AUTH\",\"token\":\"invalid\"}"));
        handler.handleMessage(admin, auth("admin", "ADMIN"));

        verify(invalid).close(argThat(status -> status.getCode() == CloseStatus.POLICY_VIOLATION.getCode()));
        verify(admin).sendMessage(argThat(message -> message.getPayload().toString().contains("AUTHENTICATED")));
        verify(admin, never()).close(argThat(status -> status.getCode() == CloseStatus.POLICY_VIOLATION.getCode()));
    }

    private TextMessage auth(String username, String role) {
        return new TextMessage("{\"type\":\"AUTH\",\"token\":\"" + jwtService.generateToken(username, role) + "\"}");
    }

    private WebSocketSession session(String id) {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn(id);
        when(session.isOpen()).thenReturn(true);
        return session;
    }
}
