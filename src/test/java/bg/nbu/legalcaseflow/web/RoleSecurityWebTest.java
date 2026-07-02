package bg.nbu.legalcaseflow.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class RoleSecurityWebTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @WithMockUser(username = "ivanov", roles = "LAWYER")
    void lawyerCannotCreateAnotherLawyer() throws Exception {
        mockMvc.perform(post("/api/lawyers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "registrationNumber": "BAR-9999",
                                  "fullName": "Нов адвокат",
                                  "specialty": "Тест"
                                }
                                """))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "maria", roles = "CLIENT")
    void clientLegalServicesEndpointReturnsOnlyOwnHistory() throws Exception {
        mockMvc.perform(get("/api/legal-services"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$[0].clientName").value("Мария Стоянова"));
    }

    @Test
    @WithMockUser(username = "maria", roles = "CLIENT")
    void clientCannotUseAccountingExports() throws Exception {
        mockMvc.perform(get("/api/invoices/export.csv"))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/invoices/saf-t-lite.csv"))
                .andExpect(status().isForbidden());
    }
}
