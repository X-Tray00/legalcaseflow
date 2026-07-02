package bg.nbu.legalcaseflow.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Regression coverage for the hardening fixes (M1 error handling, M2 CSV injection,
 * M3 401-vs-403, L2 username validation, L4 invoice-number conflict).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ErrorHandlingAndHardeningWebTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    // --- M3: missing authentication is 401, wrong role stays 403 ---

    @Test
    void anonymousRequestToProtectedEndpointReturns401() throws Exception {
        mockMvc.perform(get("/api/clients"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    @WithMockUser(username = "maria", roles = "CLIENT")
    void authenticatedButForbiddenRoleStillReturns403() throws Exception {
        mockMvc.perform(get("/api/audit-events"))
                .andExpect(status().isForbidden());
    }

    // --- M1: standard MVC failures map to the right status and never leak internals ---

    @Test
    void emptyRequestBodyReturns400WithoutLeakingInternals() throws Exception {
        mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Malformed or missing request data"));
    }

    @Test
    @WithMockUser(username = "admin", roles = "ADMIN")
    void getOnPostOnlyEndpointReturns405() throws Exception {
        mockMvc.perform(get("/api/document-drafts"))
                .andExpect(status().isMethodNotAllowed());
    }

    @Test
    @WithMockUser(username = "admin", roles = "ADMIN")
    void wrongContentTypeReturns415() throws Exception {
        mockMvc.perform(post("/api/clients").contentType(MediaType.TEXT_PLAIN).content("x"))
                .andExpect(status().isUnsupportedMediaType());
    }

    @Test
    @WithMockUser(username = "admin", roles = "ADMIN")
    void malformedJsonReturns400() throws Exception {
        mockMvc.perform(post("/api/clients").contentType(MediaType.APPLICATION_JSON).content("{bad"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(username = "admin", roles = "ADMIN")
    void pathVariableTypeMismatchReturns400() throws Exception {
        mockMvc.perform(get("/api/clients/not-a-number"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(username = "admin", roles = "ADMIN")
    void missingRequiredSearchParamReturns400() throws Exception {
        mockMvc.perform(get("/api/search"))
                .andExpect(status().isBadRequest());
    }

    // --- L2: usernames with markup are rejected by validation ---

    @Test
    @WithMockUser(username = "admin", roles = "ADMIN")
    void htmlUsernameIsRejected() throws Exception {
        mockMvc.perform(post("/api/clients").contentType(MediaType.APPLICATION_JSON).content("""
                        {
                          "fullName": "XSS Test",
                          "identifier": "XSS-1",
                          "contact": "x@x.bg",
                          "legalAidEligible": false,
                          "leadLawyerId": 1,
                          "account": { "username": "<img onerror=x>", "password": "password123" }
                        }
                        """))
                .andExpect(status().isBadRequest());
    }

    // --- M2 + L4: build a malicious client, invoice it, then export ---

    @Test
    @WithMockUser(username = "admin", roles = "ADMIN")
    void csvExportNeutralizesFormulaInjectionAndRejectsDuplicateNumbers() throws Exception {
        long clientId = createAndReadId("/api/clients", """
                {
                  "fullName": "=2+5+cmd|calc",
                  "identifier": "INJ-1",
                  "contact": "x@x.bg",
                  "legalAidEligible": false,
                  "leadLawyerId": 1
                }
                """);
        long serviceId = createAndReadId("/api/legal-services", """
                {
                  "date": "2026-01-01",
                  "lawyerId": 1,
                  "clientId": %d,
                  "caseTypeId": 1,
                  "fee": 50.00,
                  "paid": false
                }
                """.formatted(clientId));
        long invoiceId = createAndReadId("/api/invoices", """
                {
                  "legalServiceId": %d,
                  "issueDate": "2026-01-01",
                  "dueDate": "2026-02-01"
                }
                """.formatted(serviceId));

        String csv = mockMvc.perform(get("/api/invoices/export.csv"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // The malicious cell must be present but neutralized (apostrophe-prefixed),
        // and no data line may start with a spreadsheet formula trigger.
        assertThat(csv).contains("'=2+5+cmd|calc");
        for (String line : csv.split("\n")) {
            assertThat(line.isEmpty() || "=+-@".indexOf(line.charAt(0)) < 0)
                    .as("CSV line must not start with a formula trigger: %s", line)
                    .isTrue();
        }

        // L4: re-using the generated invoice number is a 409 conflict, not a 400.
        String existingNumber = mockMvc.perform(get("/api/invoices/" + invoiceId))
                .andReturn().getResponse().getContentAsString();
        String number = objectMapper.readTree(existingNumber).get("invoiceNumber").asText();
        mockMvc.perform(post("/api/invoices").contentType(MediaType.APPLICATION_JSON).content("""
                        {
                          "invoiceNumber": "%s",
                          "legalServiceId": %d,
                          "issueDate": "2026-01-01",
                          "dueDate": "2026-02-01"
                        }
                        """.formatted(number, serviceId)))
                .andExpect(status().isConflict());
    }

    private long createAndReadId(String url, String json) throws Exception {
        String body = mockMvc.perform(post(url).contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().is2xxSuccessful())
                .andReturn().getResponse().getContentAsString();
        JsonNode node = objectMapper.readTree(body);
        return node.get("id").asLong();
    }
}
