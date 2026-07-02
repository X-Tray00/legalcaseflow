package bg.nbu.legalcaseflow.web.dto.response;

import java.util.List;

public record SearchResponse(
        String query,
        int total,
        List<String> interpretedTerms,
        List<SearchResultResponse> results
) {
}
