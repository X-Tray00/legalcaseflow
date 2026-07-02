package bg.nbu.legalcaseflow.web;

import bg.nbu.legalcaseflow.repository.ClientRepository;
import bg.nbu.legalcaseflow.repository.LawyerRepository;
import bg.nbu.legalcaseflow.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AccountProvisioningWebTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ClientRepository clientRepository;

    @Autowired
    private LawyerRepository lawyerRepository;

    @Test
    @WithMockUser(username = "ivanov", roles = "LAWYER")
    void lawyerCreatesClientAccountAssignedToSelf() throws Exception {
        mockMvc.perform(post("/api/clients")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "fullName": "Нов Клиент",
                                  "identifier": "TEST-CLIENT-001",
                                  "contact": "client@example.com",
                                  "legalAidEligible": false,
                                  "leadLawyerId": 999999,
                                  "account": {
                                    "username": "new-client",
                                    "password": "temporary123"
                                  }
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.username").value("new-client"))
                .andExpect(jsonPath("$.fullName").value("Нов Клиент"));

        var created = userRepository.findByUsername("new-client").orElseThrow();
        var creator = userRepository.findByUsername("ivanov").orElseThrow();
        assertThat(created.getClient().getLeadLawyer().getId()).isEqualTo(creator.getLawyer().getId());
    }

    @Test
    @WithMockUser(username = "ivanov", roles = "LAWYER")
    void lawyerCreatesClientWithoutAccountAssignedToSelf() throws Exception {
        mockMvc.perform(post("/api/clients")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "fullName": "Клиент без акаунт",
                                  "identifier": "TEST-CLIENT-002",
                                  "legalAidEligible": false
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.username").isEmpty());

        var created = clientRepository.findAll().stream()
                .filter(client -> "TEST-CLIENT-002".equals(client.getIdentifier()))
                .findFirst().orElseThrow();
        var creator = userRepository.findByUsername("ivanov").orElseThrow();
        assertThat(created.getLeadLawyer().getId()).isEqualTo(creator.getLawyer().getId());
        assertThat(userRepository.findByClient_Id(created.getId())).isEmpty();
    }

    @Test
    @WithMockUser(username = "admin", roles = "ADMIN")
    void adminCreatesLinkedClientAccount() throws Exception {
        mockMvc.perform(post("/api/clients")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "fullName": "Клиент от Админ",
                                  "identifier": "TEST-CLIENT-003",
                                  "legalAidEligible": true,
                                  "account": {
                                    "username": "admin-created-client",
                                    "password": "temporary123"
                                  }
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.username").value("admin-created-client"));

        assertThat(userRepository.findByUsername("admin-created-client").orElseThrow().getClient()).isNotNull();
    }

    @Test
    @WithMockUser(username = "admin", roles = "ADMIN")
    void adminCreatesClientWithoutAccount() throws Exception {
        mockMvc.perform(post("/api/clients")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "fullName": "Админ клиент без акаунт",
                                  "identifier": "TEST-CLIENT-004",
                                  "legalAidEligible": false
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.username").isEmpty());
    }

    @Test
    @WithMockUser(username = "admin", roles = "ADMIN")
    void adminCreatesLinkedLawyerAccount() throws Exception {
        mockMvc.perform(post("/api/lawyers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "registrationNumber": "TEST-BAR-001",
                                  "fullName": "Адвокат от Админ",
                                  "specialty": "Търговско право",
                                  "account": {
                                    "username": "admin-created-lawyer",
                                    "password": "temporary123"
                                  }
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.username").value("admin-created-lawyer"));

        assertThat(userRepository.findByUsername("admin-created-lawyer").orElseThrow().getLawyer()).isNotNull();
    }

    @Test
    @WithMockUser(username = "admin", roles = "ADMIN")
    void adminCreatesLawyerWithoutAccount() throws Exception {
        mockMvc.perform(post("/api/lawyers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "registrationNumber": "TEST-BAR-002",
                                  "fullName": "Адвокат без акаунт",
                                  "specialty": "Гражданско право"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.username").isEmpty());
    }

    @Test
    @WithMockUser(username = "ivanov", roles = "LAWYER")
    void lawyerAddsAccountWhenEditingClientWithoutAccount() throws Exception {
        mockMvc.perform(post("/api/clients")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "fullName": "Доверител за редакция",
                                  "identifier": "TEST-CLIENT-EDIT-001",
                                  "contact": "before@example.com",
                                  "legalAidEligible": false
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.username").isEmpty());

        var client = clientRepository.findAll().stream()
                .filter(candidate -> "TEST-CLIENT-EDIT-001".equals(candidate.getIdentifier()))
                .findFirst().orElseThrow();

        mockMvc.perform(put("/api/clients/{id}", client.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "fullName": "Редактиран доверител",
                                  "identifier": "TEST-CLIENT-EDIT-001",
                                  "contact": "after@example.com",
                                  "legalAidEligible": true,
                                  "account": {
                                    "username": "edited-client-account",
                                    "password": "temporary123"
                                  }
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fullName").value("Редактиран доверител"))
                .andExpect(jsonPath("$.username").value("edited-client-account"));

        assertThat(userRepository.findByUsername("edited-client-account").orElseThrow().getClient().getId())
                .isEqualTo(client.getId());
    }

    @Test
    @WithMockUser(username = "admin", roles = "ADMIN")
    void adminAddsAccountWhenEditingLawyerWithoutAccount() throws Exception {
        mockMvc.perform(post("/api/lawyers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "registrationNumber": "TEST-BAR-EDIT-001",
                                  "fullName": "Адвокат за редакция",
                                  "specialty": "Гражданско право"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.username").isEmpty());

        var lawyer = lawyerRepository.findByRegistrationNumber("TEST-BAR-EDIT-001").orElseThrow();

        mockMvc.perform(put("/api/lawyers/{id}", lawyer.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "registrationNumber": "TEST-BAR-EDIT-001",
                                  "fullName": "Редактиран адвокат",
                                  "specialty": "Търговско право",
                                  "account": {
                                    "username": "edited-lawyer-account",
                                    "password": "temporary123"
                                  }
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fullName").value("Редактиран адвокат"))
                .andExpect(jsonPath("$.username").value("edited-lawyer-account"));

        assertThat(userRepository.findByUsername("edited-lawyer-account").orElseThrow().getLawyer().getId())
                .isEqualTo(lawyer.getId());
    }

    @Test
    @WithMockUser(username = "admin", roles = "ADMIN")
    void adminCanEditExistingClientAccountCredentials() throws Exception {
        var maria = userRepository.findByUsername("maria").orElseThrow();
        Long clientId = maria.getClient().getId();

        mockMvc.perform(put("/api/clients/{id}", clientId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "fullName": "Мария Преименувана",
                                  "identifier": "EDIT-ACCOUNT-001",
                                  "legalAidEligible": false,
                                  "account": {
                                    "username": "maria-renamed",
                                    "password": "newpassword123"
                                  }
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("maria-renamed"))
                .andExpect(jsonPath("$.fullName").value("Мария Преименувана"));

        assertThat(userRepository.findByUsername("maria")).isEmpty();
        assertThat(userRepository.findByUsername("maria-renamed").orElseThrow().getClient().getId())
                .isEqualTo(clientId);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @WithMockUser(username = "ivanov", roles = "LAWYER")
    void lawyerCannotEditExistingClientAccount() throws Exception {
        var maria = userRepository.findByUsername("maria").orElseThrow();
        Long clientId = maria.getClient().getId();
        String originalName = clientRepository.findById(clientId).orElseThrow().getFullName();

        mockMvc.perform(put("/api/clients/{id}", clientId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "fullName": "Не трябва да се запази",
                                  "identifier": "LAWYER-EDIT-ACCOUNT",
                                  "legalAidEligible": false,
                                  "account": {
                                    "username": "maria-hijack",
                                    "password": "temporary123"
                                  }
                                }
                                """))
                .andExpect(status().isConflict());

        assertThat(clientRepository.findById(clientId).orElseThrow().getFullName()).isEqualTo(originalName);
        assertThat(userRepository.findByUsername("maria")).isPresent();
        assertThat(userRepository.findByUsername("maria-hijack")).isEmpty();
    }

    @Test
    @WithMockUser(username = "admin", roles = "ADMIN")
    void adminCanEditExistingLawyerAccountCredentials() throws Exception {
        var ivanov = userRepository.findByUsername("ivanov").orElseThrow();
        Long lawyerId = ivanov.getLawyer().getId();
        String registrationNumber = ivanov.getLawyer().getRegistrationNumber();

        mockMvc.perform(put("/api/lawyers/{id}", lawyerId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "registrationNumber": "%s",
                                  "fullName": "Иван Преименуван",
                                  "account": {
                                    "username": "ivanov-renamed",
                                    "password": "newpassword123"
                                  }
                                }
                                """.formatted(registrationNumber)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("ivanov-renamed"));

        assertThat(userRepository.findByUsername("ivanov")).isEmpty();
        assertThat(userRepository.findByUsername("ivanov-renamed").orElseThrow().getLawyer().getId())
                .isEqualTo(lawyerId);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @WithMockUser(username = "admin", roles = "ADMIN")
    void duplicateUsernameOnEditRollsBackProfileChanges() throws Exception {
        mockMvc.perform(post("/api/clients")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "fullName": "Профил без акаунт",
                                  "identifier": "TEST-EDIT-ROLLBACK-001",
                                  "legalAidEligible": false
                                }
                                """))
                .andExpect(status().isCreated());

        var client = clientRepository.findAll().stream()
                .filter(candidate -> "TEST-EDIT-ROLLBACK-001".equals(candidate.getIdentifier()))
                .findFirst().orElseThrow();

        mockMvc.perform(put("/api/clients/{id}", client.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "fullName": "Не трябва да се запази",
                                  "identifier": "TEST-EDIT-ROLLBACK-001",
                                  "legalAidEligible": true,
                                  "account": {
                                    "username": "maria",
                                    "password": "temporary123"
                                  }
                                }
                                """))
                .andExpect(status().isBadRequest());

        var unchanged = clientRepository.findById(client.getId()).orElseThrow();
        assertThat(unchanged.getFullName()).isEqualTo("Профил без акаунт");
        assertThat(unchanged.isLegalAidEligible()).isFalse();
        assertThat(userRepository.findByClient_Id(client.getId())).isEmpty();
    }

    @Test
    @WithMockUser(username = "ivanov", roles = "LAWYER")
    void lawyerCannotCreateLawyer() throws Exception {
        mockMvc.perform(post("/api/lawyers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "registrationNumber": "TEST-BAR-003",
                                  "fullName": "Забранен Адвокат"
                                }
                                """))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "admin", roles = "ADMIN")
    void accountsEndpointIsRemoved() throws Exception {
        mockMvc.perform(get("/api/accounts"))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(username = "admin", roles = "ADMIN")
    void createdClientAccountCanLoginWithCorrectProfileId() throws Exception {
        String response = mockMvc.perform(post("/api/clients")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "fullName": "Клиент за вход",
                                  "identifier": "TEST-CLIENT-005",
                                  "legalAidEligible": false,
                                  "account": {
                                    "username": "login-client",
                                    "password": "temporary123"
                                  }
                                }
                                """))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        Long clientId = userRepository.findByUsername("login-client").orElseThrow().getClient().getId();
        assertThat(response).contains("\"username\":\"login-client\"");

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "username": "login-client",
                                  "password": "temporary123"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.clientId").value(clientId))
                .andExpect(jsonPath("$.role").value("CLIENT"));
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @WithMockUser(username = "admin", roles = "ADMIN")
    void duplicateUsernameRollsBackProfileCreation() throws Exception {
        mockMvc.perform(post("/api/clients")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "fullName": "Трябва да бъде rollback",
                                  "identifier": "TEST-ROLLBACK-001",
                                  "legalAidEligible": false,
                                  "account": {
                                    "username": "maria",
                                    "password": "temporary123"
                                  }
                                }
                                """))
                .andExpect(status().isBadRequest());

        assertThat(clientRepository.findAll()).noneMatch(client -> "TEST-ROLLBACK-001".equals(client.getIdentifier()));
    }

    @Test
    @WithMockUser(username = "admin", roles = "ADMIN")
    void shortPasswordIsRejectedBeforeCreation() throws Exception {
        mockMvc.perform(post("/api/lawyers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "registrationNumber": "TEST-BAR-SHORT",
                                  "fullName": "Кратка Парола",
                                  "account": {
                                    "username": "short-password",
                                    "password": "short"
                                  }
                                }
                                """))
                .andExpect(status().isBadRequest());

        assertThat(lawyerRepository.findByRegistrationNumber("TEST-BAR-SHORT")).isEmpty();
    }

    @Test
    @WithMockUser(username = "admin", roles = "ADMIN")
    void deletingProfileDeletesLinkedAccount() throws Exception {
        mockMvc.perform(post("/api/clients")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "fullName": "Клиент за изтриване",
                                  "identifier": "TEST-DELETE-001",
                                  "legalAidEligible": false,
                                  "account": {
                                    "username": "delete-client",
                                    "password": "temporary123"
                                  }
                                }
                                """))
                .andExpect(status().isCreated());

        Long clientId = userRepository.findByUsername("delete-client").orElseThrow().getClient().getId();
        mockMvc.perform(delete("/api/clients/{id}", clientId))
                .andExpect(status().isNoContent());

        assertThat(userRepository.findByUsername("delete-client").orElseThrow().isActive()).isFalse();
        assertThat(clientRepository.findById(clientId)).isEmpty();
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @WithMockUser(username = "admin", roles = "ADMIN")
    void deletingUsedProfileReturnsConflictAndRollsBackAccountDeletion() throws Exception {
        var maria = userRepository.findByUsername("maria").orElseThrow();
        Long clientId = maria.getClient().getId();

        mockMvc.perform(delete("/api/clients/{id}", clientId))
                .andExpect(status().isConflict());

        assertThat(clientRepository.findById(clientId)).isPresent();
        assertThat(userRepository.findByUsername("maria")).isPresent();
    }
}
