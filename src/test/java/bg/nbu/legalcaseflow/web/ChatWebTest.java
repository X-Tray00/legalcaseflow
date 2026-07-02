package bg.nbu.legalcaseflow.web;

import bg.nbu.legalcaseflow.domain.*;
import bg.nbu.legalcaseflow.repository.*;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ChatWebTest {

    @Autowired MockMvc mockMvc;
    @Autowired UserRepository userRepository;
    @Autowired ClientRepository clientRepository;
    @Autowired LawyerRepository lawyerRepository;
    @Autowired ChatConversationRepository conversationRepository;
    @Autowired ChatMessageRepository messageRepository;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired EntityManager entityManager;

    @Test
    void clientSeesOppositeRoleContactsConversationsAndUnreadCount() throws Exception {
        mockMvc.perform(get("/api/chats/contacts").with(user("maria").roles("CLIENT")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.displayName == 'Иван Иванов')]").exists())
                .andExpect(jsonPath("$[?(@.role == 'LAWYER')]").exists());

        mockMvc.perform(get("/api/chats/conversations").with(user("maria").roles("CLIENT")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].counterpartName").value("Иван Иванов"))
                .andExpect(jsonPath("$[0].unreadCount").value(1));

        mockMvc.perform(get("/api/chats/unread-count").with(user("maria").roles("CLIENT")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unreadCount").value(1));
    }

    @Test
    void creatingExistingConversationIsIdempotentForBothParticipants() throws Exception {
        Long ivanovId = userRepository.findByUsername("ivanov").orElseThrow().getId();
        Long mariaId = userRepository.findByUsername("maria").orElseThrow().getId();
        Long existingId = conversationRepository.findAll().getFirst().getId();

        mockMvc.perform(post("/api/chats/conversations")
                        .with(user("maria").roles("CLIENT"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"counterpartUserId\":" + ivanovId + "}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(existingId));

        mockMvc.perform(post("/api/chats/conversations")
                        .with(user("ivanov").roles("LAWYER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"counterpartUserId\":" + mariaId + "}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(existingId));

        assertThat(conversationRepository.count()).isEqualTo(1);
    }

    @Test
    void lawyerCanCreateANewConversationWithAnotherClientAccount() throws Exception {
        Long stefanId = userRepository.findByUsername("stefan").orElseThrow().getId();

        mockMvc.perform(post("/api/chats/conversations")
                        .with(user("ivanov").roles("LAWYER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"counterpartUserId\":" + stefanId + "}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.counterpartName").value("Стефан Колев"))
                .andExpect(jsonPath("$.counterpartRole").value("CLIENT"));

        assertThat(conversationRepository.count()).isEqualTo(2);
    }

    @Test
    void adminCanStartConversationsWithClientsAndLawyersButCannotReadUnrelatedChats() throws Exception {
        Long mariaId = userRepository.findByUsername("maria").orElseThrow().getId();
        Long ivanovId = userRepository.findByUsername("ivanov").orElseThrow().getId();
        Long existingClientLawyerConversation = conversationRepository.findAll().getFirst().getId();

        mockMvc.perform(get("/api/chats/conversations").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
        mockMvc.perform(get("/api/chats/contacts").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.role == 'CLIENT')]").exists())
                .andExpect(jsonPath("$[?(@.role == 'LAWYER')]").exists());

        mockMvc.perform(get("/api/chats/conversations/{id}/messages", existingClientLawyerConversation)
                        .with(user("admin").roles("ADMIN")))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/chats/conversations")
                        .with(user("admin").roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"counterpartUserId\":" + mariaId + "}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.counterpartRole").value("CLIENT"));

        mockMvc.perform(post("/api/chats/conversations")
                        .with(user("admin").roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"counterpartUserId\":" + ivanovId + "}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.counterpartRole").value("LAWYER"));
    }

    @Test
    void lawyerCanStartWithAdminButClientCannot() throws Exception {
        Long adminId = userRepository.findByUsername("admin").orElseThrow().getId();

        mockMvc.perform(get("/api/chats/contacts").with(user("ivanov").roles("LAWYER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.role == 'ADMIN')]").exists());

        String created = mockMvc.perform(post("/api/chats/conversations")
                        .with(user("ivanov").roles("LAWYER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"counterpartUserId\":" + adminId + "}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.counterpartRole").value("ADMIN"))
                .andReturn().getResponse().getContentAsString();
        long conversationId = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(created).path("id").asLong();

        mockMvc.perform(post("/api/chats/conversations")
                        .with(user("admin").roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"counterpartUserId\":"
                                + userRepository.findByUsername("ivanov").orElseThrow().getId() + "}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(conversationId));

        mockMvc.perform(get("/api/chats/contacts").with(user("maria").roles("CLIENT")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.role == 'ADMIN')]").isEmpty());
        mockMvc.perform(post("/api/chats/conversations")
                        .with(user("maria").roles("CLIENT"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"counterpartUserId\":" + adminId + "}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void sameRoleAndNonParticipantAreRejected() throws Exception {
        User secondClient = createClientUser("same-role-client");
        Long ivanovId = userRepository.findByUsername("ivanov").orElseThrow().getId();
        Long conversationId = conversationRepository.findAll().getFirst().getId();

        mockMvc.perform(post("/api/chats/conversations")
                        .with(user("maria").roles("CLIENT"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"counterpartUserId\":" + secondClient.getId() + "}"))
                .andExpect(status().isBadRequest());

        User secondLawyer = createLawyerUser("other-lawyer");
        mockMvc.perform(get("/api/chats/conversations/{id}/messages", conversationId)
                        .with(user(secondLawyer.getUsername()).roles("LAWYER")))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/chats/conversations")
                        .with(user(secondLawyer.getUsername()).roles("LAWYER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"counterpartUserId\":" + ivanovId + "}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void sendingReadingAndImmutableHistoryWork() throws Exception {
        Long conversationId = conversationRepository.findAll().getFirst().getId();

        mockMvc.perform(post("/api/chats/conversations/{id}/messages", conversationId)
                        .with(user("maria").roles("CLIENT"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"  Нов въпрос  \"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.content").value("Нов въпрос"))
                .andExpect(jsonPath("$.own").value(true));

        mockMvc.perform(get("/api/chats/conversations/{id}/messages?limit=50", conversationId)
                        .with(user("ivanov").roles("LAWYER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[2].content").value("Нов въпрос"))
                .andExpect(jsonPath("$[2].own").value(false));

        mockMvc.perform(post("/api/chats/conversations/{id}/read", conversationId)
                        .with(user("ivanov").roles("LAWYER")))
                .andExpect(status().isNoContent());

        entityManager.clear();
        assertThat(messageRepository.findTopByConversation_IdOrderByIdDesc(conversationId).orElseThrow().getReadAt())
                .isNotNull();

        mockMvc.perform(put("/api/chats/conversations/{id}/messages/1", conversationId)
                .with(user("maria").roles("CLIENT"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"edited\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void markingMessagesReadKeepsLazyConversationRelationsAvailable() throws Exception {
        User clientUser = createClientUser("read-client");
        User lawyerUser = createLawyerUser("read-lawyer");

        ChatConversation conversation = new ChatConversation();
        conversation.setClient(clientUser.getClient());
        conversation.setLawyer(lawyerUser.getLawyer());
        conversation.setCreatedAt(Instant.now());
        conversation.setLastActivityAt(Instant.now());
        conversation = conversationRepository.save(conversation);

        ChatMessage message = new ChatMessage();
        message.setConversation(conversation);
        message.setSender(lawyerUser);
        message.setContent("Непрочетено съобщение");
        message.setSentAt(Instant.now());
        messageRepository.save(message);

        try {
            mockMvc.perform(post("/api/chats/conversations/{id}/read", conversation.getId())
                            .with(user(clientUser.getUsername()).roles("CLIENT")))
                    .andExpect(status().isNoContent());

            assertThat(messageRepository.findById(message.getId()).orElseThrow().getReadAt()).isNotNull();
        } finally {
            messageRepository.deleteById(message.getId());
            conversationRepository.deleteById(conversation.getId());
            userRepository.deleteById(clientUser.getId());
            userRepository.deleteById(lawyerUser.getId());
            clientRepository.deleteById(clientUser.getClient().getId());
            lawyerRepository.deleteById(lawyerUser.getLawyer().getId());
        }
    }

    @Test
    void messageValidationAndPaginationWork() throws Exception {
        Long conversationId = conversationRepository.findAll().getFirst().getId();

        mockMvc.perform(post("/api/chats/conversations/{id}/messages", conversationId)
                        .with(user("maria").roles("CLIENT"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"   \"}"))
                .andExpect(status().isBadRequest());

        String tooLong = "x".repeat(2001);
        mockMvc.perform(post("/api/chats/conversations/{id}/messages", conversationId)
                        .with(user("maria").roles("CLIENT"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"" + tooLong + "\"}"))
                .andExpect(status().isBadRequest());

        Long beforeId = messageRepository.findTopByConversation_IdOrderByIdDesc(conversationId).orElseThrow().getId();
        mockMvc.perform(get("/api/chats/conversations/{id}/messages?limit=1&beforeId={beforeId}",
                        conversationId, beforeId)
                        .with(user("maria").roles("CLIENT")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").exists());
    }

    @Test
    void contactsExcludeProfilesWithoutLoginAccounts() throws Exception {
        Lawyer noAccount = new Lawyer();
        noAccount.setRegistrationNumber("NO-CHAT-" + UUID.randomUUID());
        noAccount.setFullName("Адвокат без акаунт");
        lawyerRepository.save(noAccount);

        mockMvc.perform(get("/api/chats/contacts").with(user("maria").roles("CLIENT")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.displayName == 'Адвокат без акаунт')]").isEmpty());
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void deletingProfileWithChatHistoryReturnsConflictAndRollsBackAccountDeletion() throws Exception {
        User clientUser = createClientUser("chat-delete-client");
        User lawyerUser = createLawyerUser("chat-delete-lawyer");

        mockMvc.perform(post("/api/chats/conversations")
                        .with(user(clientUser.getUsername()).roles("CLIENT"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"counterpartUserId\":" + lawyerUser.getId() + "}"))
                .andExpect(status().isCreated());

        Long clientId = userRepository.findByUsername(clientUser.getUsername()).orElseThrow().getClient().getId();
        mockMvc.perform(delete("/api/clients/{id}", clientId).with(user("admin").roles("ADMIN")))
                .andExpect(status().isConflict());

        assertThat(clientRepository.findById(clientId)).isPresent();
        assertThat(userRepository.findByUsername(clientUser.getUsername())).isPresent();
    }

    private User createClientUser(String prefix) {
        String suffix = UUID.randomUUID().toString();
        Client client = new Client();
        client.setFullName(prefix);
        client.setIdentifier("CLIENT-" + suffix);
        client.setLegalAidEligible(false);
        clientRepository.save(client);

        User user = new User();
        user.setUsername(prefix + "-" + suffix);
        user.setPassword(passwordEncoder.encode("temporary123"));
        user.setRole(Role.CLIENT);
        user.setClient(client);
        return userRepository.save(user);
    }

    private User createLawyerUser(String prefix) {
        String suffix = UUID.randomUUID().toString();
        Lawyer lawyer = new Lawyer();
        lawyer.setRegistrationNumber("LAWYER-" + suffix);
        lawyer.setFullName(prefix);
        lawyerRepository.save(lawyer);

        User user = new User();
        user.setUsername(prefix + "-" + suffix);
        user.setPassword(passwordEncoder.encode("temporary123"));
        user.setRole(Role.LAWYER);
        user.setLawyer(lawyer);
        return userRepository.save(user);
    }
}
