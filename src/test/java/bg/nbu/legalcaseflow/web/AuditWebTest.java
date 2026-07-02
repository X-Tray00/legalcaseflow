package bg.nbu.legalcaseflow.web;

import bg.nbu.legalcaseflow.domain.AuditAction;
import bg.nbu.legalcaseflow.domain.AuditEvent;
import bg.nbu.legalcaseflow.repository.AuditEventRepository;
import bg.nbu.legalcaseflow.repository.ChatConversationRepository;
import bg.nbu.legalcaseflow.repository.UserRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AuditWebTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AuditEventRepository auditEventRepository;

    @Autowired
    private ChatConversationRepository chatConversationRepository;

    @Autowired
    private UserRepository userRepository;

    @Test
    void onlyAdminCanReadAuditLogAndDeniedAccessIsRecorded() throws Exception {
        mockMvc.perform(get("/api/audit-events").with(user("ivanov").roles("LAWYER")))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/audit-events").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray());

        assertThat(auditEventRepository.findAll()).anyMatch(event ->
                event.getAction() == AuditAction.ACCESS_DENIED && "ivanov".equals(event.getActorUsername()));
    }

    @Test
    void adminCanRestoreUpdateAndDeleteWithSameId() throws Exception {
        JsonNode created = json(mockMvc.perform(post("/api/case-types")
                        .with(user("admin").roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Audit restore type\",\"description\":\"before\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        long id = created.path("id").asLong();

        mockMvc.perform(put("/api/case-types/{id}", id)
                        .with(user("admin").roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Audit restore type\",\"description\":\"after\"}"))
                .andExpect(status().isOk());

        AuditEvent updateEvent = latest("case-types", id, AuditAction.UPDATE);
        mockMvc.perform(post("/api/audit-events/{id}/restore", updateEvent.getId())
                        .with(user("admin").roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/case-types/{id}", id).with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.description").value("before"));

        mockMvc.perform(delete("/api/case-types/{id}", id).with(user("admin").roles("ADMIN")))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/case-types/{id}", id).with(user("admin").roles("ADMIN")))
                .andExpect(status().isNotFound());

        AuditEvent deleteEvent = latest("case-types", id, AuditAction.DELETE);
        mockMvc.perform(post("/api/audit-events/{id}/restore", deleteEvent.getId())
                        .with(user("admin").roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/case-types/{id}", id).with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id));
    }

    @Test
    void repeatedOrStaleRestoreReturnsConflict() throws Exception {
        JsonNode created = json(mockMvc.perform(post("/api/case-types")
                        .with(user("admin").roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Audit stale type\",\"description\":\"before\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        long id = created.path("id").asLong();
        AuditEvent createEvent = latest("case-types", id, AuditAction.CREATE);

        mockMvc.perform(put("/api/case-types/{id}", id)
                        .with(user("admin").roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Audit stale type\",\"description\":\"changed\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/audit-events/{id}/restore", createEvent.getId())
                        .with(user("admin").roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isConflict());

        AuditEvent update = latest("case-types", id, AuditAction.UPDATE);
        mockMvc.perform(post("/api/audit-events/{id}/restore", update.getId())
                        .with(user("admin").roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/audit-events/{id}/restore", update.getId())
                        .with(user("admin").roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isConflict());
    }

    @Test
    void softDeletedRecordsAreHiddenFromCrudAndSearch() throws Exception {
        JsonNode created = json(mockMvc.perform(post("/api/case-types")
                        .with(user("admin").roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Hidden audit type\",\"description\":\"must disappear\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        long id = created.path("id").asLong();

        mockMvc.perform(delete("/api/case-types/{id}", id).with(user("admin").roles("ADMIN")))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/case-types").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == %d)]".formatted(id)).doesNotExist());
        mockMvc.perform(get("/api/search").param("q", "Hidden audit type")
                        .with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[?(@.id == %d)]".formatted(id)).doesNotExist());
    }

    @Test
    void restoringCreateReturnsConflictWhenRecordHasDependencies() throws Exception {
        JsonNode caseType = json(mockMvc.perform(post("/api/case-types")
                        .with(user("admin").roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Audit dependency type\",\"description\":\"used\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        long caseTypeId = caseType.path("id").asLong();
        long clientId = userRepository.findByUsername("maria").orElseThrow().getClient().getId();
        long lawyerId = userRepository.findByUsername("ivanov").orElseThrow().getLawyer().getId();

        mockMvc.perform(post("/api/legal-services")
                        .with(user("admin").roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "date": "2035-02-03",
                                  "lawyerId": %d,
                                  "clientId": %d,
                                  "caseTypeId": %d,
                                  "description": "Dependency restore check",
                                  "fee": 100,
                                  "paid": false
                                }
                                """.formatted(lawyerId, clientId, caseTypeId)))
                .andExpect(status().isCreated());

        AuditEvent createEvent = latest("case-types", caseTypeId, AuditAction.CREATE);
        mockMvc.perform(post("/api/audit-events/{id}/restore", createEvent.getId())
                        .with(user("admin").roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isConflict());
    }

    @Test
    void restoringDeletedProfileReactivatesItsLoginAccount() throws Exception {
        JsonNode created = json(mockMvc.perform(post("/api/clients")
                        .with(user("admin").roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "fullName": "Audit restore client",
                                  "identifier": "AUDIT-RESTORE-CLIENT",
                                  "legalAidEligible": false,
                                  "account": {"username": "audit-restore-client", "password": "temporary123"}
                                }
                                """))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        long clientId = created.path("id").asLong();

        mockMvc.perform(delete("/api/clients/{id}", clientId).with(user("admin").roles("ADMIN")))
                .andExpect(status().isNoContent());
        assertThat(userRepository.findByUsername("audit-restore-client").orElseThrow().isActive()).isFalse();

        AuditEvent deleteEvent = latest("clients", clientId, AuditAction.DELETE);
        mockMvc.perform(post("/api/audit-events/{id}/restore", deleteEvent.getId())
                        .with(user("admin").roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());

        assertThat(userRepository.findByUsername("audit-restore-client").orElseThrow().isActive()).isTrue();
        mockMvc.perform(get("/api/clients/{id}", clientId).with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(clientId));
    }

    @Test
    void chatAuditContainsMetadataButNeverMessageContent() throws Exception {
        long conversationId = chatConversationRepository.findAll().getFirst().getId();
        String secret = "private audit test content";
        mockMvc.perform(post("/api/chats/conversations/{id}/messages", conversationId)
                        .with(user("maria").roles("CLIENT"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(java.util.Map.of("content", secret))))
                .andExpect(status().isCreated());

        AuditEvent event = latest("chat-conversations", conversationId, AuditAction.CHAT_MESSAGE_SENT);
        assertThat(event.getMetadata()).contains("clientName", "lawyerName", "counterpart");
        assertThat(event.getMetadata()).doesNotContain(secret);
        assertThat(event.getBeforeState()).isNull();
        assertThat(event.getAfterState()).isNull();
    }

    @Test
    void loginAttemptsAreAuditedWithoutCredentials() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"wrong-password\"}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"admin123\"}"))
                .andExpect(status().isOk());

        assertThat(auditEventRepository.findAll()).anyMatch(event -> event.getAction() == AuditAction.LOGIN_FAILURE);
        assertThat(auditEventRepository.findAll()).anyMatch(event -> event.getAction() == AuditAction.LOGIN_SUCCESS);
        assertThat(auditEventRepository.findAll()).allMatch(event ->
                (event.getMetadata() == null || !event.getMetadata().contains("wrong-password"))
                        && (event.getAfterState() == null || !event.getAfterState().contains("admin123")));
    }

    @Test
    void lawyerAndClientMutationsAreAttributedAndRollbackLeavesNoCreateAudit() throws Exception {
        mockMvc.perform(post("/api/case-types")
                        .with(user("ivanov").roles("LAWYER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Lawyer audit type\",\"description\":\"created by lawyer\"}"))
                .andExpect(status().isCreated());

        long clientId = userRepository.findByUsername("maria").orElseThrow().getClient().getId();
        long lawyerId = userRepository.findByUsername("ivanov").orElseThrow().getLawyer().getId();
        mockMvc.perform(post("/api/appointments")
                        .with(user("maria").roles("CLIENT"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "clientId": %d,
                                  "lawyerId": %d,
                                  "scheduledAt": "2035-01-02T10:00:00",
                                  "status": "REQUESTED",
                                  "topic": "Audit client appointment"
                                }
                                """.formatted(clientId, lawyerId)))
                .andExpect(status().isCreated());

        assertThat(auditEventRepository.findAll()).anyMatch(event ->
                event.getAction() == AuditAction.CREATE && "ivanov".equals(event.getActorUsername()));
        assertThat(auditEventRepository.findAll()).anyMatch(event ->
                event.getAction() == AuditAction.CREATE && "maria".equals(event.getActorUsername()));

        long clientCreatesBefore = auditEventRepository.findAll().stream()
                .filter(event -> event.getAction() == AuditAction.CREATE && "clients".equals(event.getResourceType()))
                .count();
        mockMvc.perform(post("/api/clients")
                        .with(user("admin").roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "fullName": "Rollback audit client",
                                  "identifier": "AUDIT-ROLLBACK-CLIENT",
                                  "legalAidEligible": false,
                                  "account": {"username": "maria", "password": "temporary123"}
                                }
                                """))
                .andExpect(status().isBadRequest());
        long clientCreatesAfter = auditEventRepository.findAll().stream()
                .filter(event -> event.getAction() == AuditAction.CREATE && "clients".equals(event.getResourceType()))
                .count();
        assertThat(clientCreatesAfter).isEqualTo(clientCreatesBefore);
    }

    private AuditEvent latest(String resource, long id, AuditAction action) {
        return auditEventRepository.findAll().stream()
                .filter(event -> resource.equals(event.getResourceType())
                        && Long.valueOf(id).equals(event.getResourceId())
                        && action == event.getAction())
                .max(java.util.Comparator.comparing(AuditEvent::getId))
                .orElseThrow();
    }

    private JsonNode json(String value) throws Exception {
        return objectMapper.readTree(value);
    }
}
