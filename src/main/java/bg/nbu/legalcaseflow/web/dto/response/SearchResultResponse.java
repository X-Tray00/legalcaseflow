package bg.nbu.legalcaseflow.web.dto.response;

import java.util.List;

public record SearchResultResponse(
        String entity,
        String entityLabel,
        Long id,
        String title,
        String subtitle,
        String snippet,
        String route,
        int score,
        String reason,
        List<String> matchedTerms
) {
}
