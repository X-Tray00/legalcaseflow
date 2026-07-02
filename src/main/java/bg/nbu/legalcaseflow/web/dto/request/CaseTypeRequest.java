package bg.nbu.legalcaseflow.web.dto.request;

import jakarta.validation.constraints.NotBlank;

public record CaseTypeRequest(
        @NotBlank String name,
        String description
) {
}

