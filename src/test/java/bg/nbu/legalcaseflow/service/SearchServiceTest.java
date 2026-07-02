package bg.nbu.legalcaseflow.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class SearchServiceTest {

    @Autowired
    private SearchService searchService;

    @Test
    @WithMockUser(username = "admin", roles = "ADMIN")
    void adminSearchesAcrossRecordTypes() {
        var result = searchService.search("развод", null, 20);

        assertThat(result.total()).isGreaterThan(0);
        assertThat(result.results()).anyMatch(r -> "CASE_TYPE".equals(r.entity()));
        assertThat(result.results()).anyMatch(r -> "LEGAL_SERVICE".equals(r.entity()));
    }

    @Test
    @WithMockUser(username = "maria", roles = "CLIENT")
    void clientSearchReturnsOnlyOwnPrivateRecords() {
        var own = searchService.search("Мария", null, 20);
        var otherClient = searchService.search("Стефан", null, 20);

        assertThat(own.results()).allMatch(r -> !"CLIENT".equals(r.entity())
                && !"LAWYER".equals(r.entity())
                && !"CASE_TYPE".equals(r.entity()));
        assertThat(otherClient.results()).noneMatch(r -> r.title().contains("Стефан") || r.snippet().contains("Стефан"));
    }

    @Test
    @WithMockUser(username = "maria", roles = "CLIENT")
    void clientCannotSearchInternalDirectoryCategories() {
        assertThat(searchService.search("Мария", "clients", 20).results()).isEmpty();
        assertThat(searchService.search("Иван", "lawyers", 20).results()).isEmpty();
        assertThat(searchService.search("Развод", "case-types", 20).results()).isEmpty();
    }

    @Test
    @WithMockUser(username = "ivanov", roles = "LAWYER")
    void lawyerSearchDoesNotReturnAnotherLawyersPrivateWork() {
        var otherLawyersWork = searchService.search("Делба", null, 20);
        var ownWork = searchService.search("искова молба", null, 20);

        assertThat(otherLawyersWork.results())
                .noneMatch(r -> "LEGAL_SERVICE".equals(r.entity())
                        || "DOCUMENT".equals(r.entity())
                        || "APPOINTMENT".equals(r.entity())
                        || "INVOICE".equals(r.entity()));
        assertThat(ownWork.results()).anyMatch(r -> "LEGAL_SERVICE".equals(r.entity()) || "DOCUMENT".equals(r.entity()));
        assertThat(searchService.search("Иван", "lawyers", 20).results()).isEmpty();
    }

    @Test
    @WithMockUser(username = "admin", roles = "ADMIN")
    void typeFilterLimitsResultCategory() {
        var result = searchService.search("Иван", "lawyers", 20);

        assertThat(result.results()).isNotEmpty();
        assertThat(result.results()).allMatch(r -> "LAWYER".equals(r.entity()));
    }

    @Test
    @WithMockUser(username = "admin", roles = "ADMIN")
    void smartSearchUnderstandsLatinTransliteration() {
        var result = searchService.search("razvod", null, 20);

        assertThat(result.interpretedTerms()).contains("развод");
        assertThat(result.results()).anyMatch(r -> "CASE_TYPE".equals(r.entity()) && r.title().equals("Развод"));
    }

    @Test
    @WithMockUser(username = "admin", roles = "ADMIN")
    void smartSearchToleratesPartialWordsAndTypos() {
        var result = searchService.search("исков молб", null, 20);

        assertThat(result.results()).anyMatch(r -> r.title().contains("Искова молба")
                || r.snippet().contains("Искова молба")
                || r.snippet().contains("искова молба"));
        assertThat(result.results()).anyMatch(r -> r.reason().contains("частично") || r.reason().contains("fuzzy"));
    }

    @Test
    @WithMockUser(username = "admin", roles = "ADMIN")
    void smartSearchUnderstandsBusinessIntentForUnpaidInvoices() {
        var result = searchService.search("неплатени фактури", null, 20);

        assertThat(result.results()).anyMatch(r -> "INVOICE".equals(r.entity()));
        assertThat(result.results()).anyMatch(r -> r.snippet().contains("ISSUED") || r.snippet().contains("DRAFT")
                || r.snippet().contains("издадена") || r.snippet().contains("чернова"));
    }

    @Test
    @WithMockUser(username = "admin", roles = "ADMIN")
    void autocompleteFindsMariaAfterTwoCharacters() {
        var result = searchService.search("Ма", null, 20);

        assertThat(result.results()).anyMatch(r -> "CLIENT".equals(r.entity()) && r.title().equals("Мария Стоянова"));
    }
}
