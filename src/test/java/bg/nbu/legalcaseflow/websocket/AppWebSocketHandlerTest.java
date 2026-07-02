package bg.nbu.legalcaseflow.websocket;

import bg.nbu.legalcaseflow.security.JwtService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class AppWebSocketHandlerTest {

    @Autowired AppWebSocketHandler handler;
    @Autowired JwtService jwtService;
    @Autowired MockMvc mockMvc;

    @Test
    void authenticatesEveryApplicationRoleAndBroadcastsEvents() throws Exception {
        WebSocketSession admin = session("app-admin-session");
        WebSocketSession maria = session("app-maria-session");

        handler.handleMessage(admin, auth("admin", "ADMIN"));
        handler.handleMessage(maria, auth("maria", "CLIENT"));
        clearInvocations(admin, maria);

        handler.broadcast(new AppWebSocketEvent(
                "RESOURCE_CHANGED", "clients", "UPDATED", null));

        verify(admin).sendMessage(argThat(message -> message.getPayload().toString().contains("RESOURCE_CHANGED")));
        verify(maria).sendMessage(argThat(message -> message.getPayload().toString().contains("\"resource\":\"clients\"")));

        handler.afterConnectionClosed(admin, CloseStatus.NORMAL);
        handler.afterConnectionClosed(maria, CloseStatus.NORMAL);
    }

    @Test
    void rejectsInvalidAuthentication() throws Exception {
        WebSocketSession invalid = session("app-invalid-session");

        handler.handleMessage(invalid, new TextMessage("{\"type\":\"AUTH\",\"token\":\"invalid\"}"));

        verify(invalid).close(argThat(status -> status.getCode() == CloseStatus.POLICY_VIOLATION.getCode()));
    }

    @Test
    void adminOnlyBroadcastDoesNotReachOtherRoles() throws Exception {
        WebSocketSession admin = session("audit-admin-session");
        WebSocketSession maria = session("audit-client-session");
        handler.handleMessage(admin, auth("admin", "ADMIN"));
        handler.handleMessage(maria, auth("maria", "CLIENT"));
        clearInvocations(admin, maria);

        handler.broadcastToRole(new AppWebSocketEvent(
                "RESOURCE_CHANGED", "audit-events", "CREATED", null), "ADMIN");

        verify(admin).sendMessage(argThat(message -> message.getPayload().toString().contains("audit-events")));
        verify(maria, never()).sendMessage(any(WebSocketMessage.class));
        handler.afterConnectionClosed(admin, CloseStatus.NORMAL);
        handler.afterConnectionClosed(maria, CloseStatus.NORMAL);
    }

    @Test
    void successfulMutationBroadcastsButFailedMutationDoesNot() throws Exception {
        WebSocketSession watcher = session("app-change-watcher");
        handler.handleMessage(watcher, auth("admin", "ADMIN"));
        clearInvocations(watcher);

        String uniqueName = "Live event " + UUID.randomUUID();
        mockMvc.perform(post("/api/case-types")
                        .with(user("admin").roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + uniqueName + "\",\"description\":\"WebSocket test\"}"))
                .andExpect(status().isCreated());

        verify(watcher).sendMessage(argThat(message -> {
            String payload = message.getPayload().toString();
            return payload.contains("RESOURCE_CHANGED")
                    && payload.contains("\"resource\":\"case-types\"")
                    && payload.contains("\"action\":\"CREATED\"");
        }));

        clearInvocations(watcher);
        mockMvc.perform(post("/api/case-types")
                        .with(user("admin").roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\",\"description\":\"invalid\"}"))
                .andExpect(status().isBadRequest());

        verify(watcher, never()).sendMessage(any(WebSocketMessage.class));
        handler.afterConnectionClosed(watcher, CloseStatus.NORMAL);
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
