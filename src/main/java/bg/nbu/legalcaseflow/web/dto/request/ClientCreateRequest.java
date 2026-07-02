package bg.nbu.legalcaseflow.web.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

public record ClientCreateRequest(
        @NotBlank String fullName,
        String identifier,
        String contact,
        boolean legalAidEligible,
        Long leadLawyerId,
        @Valid AccountCredentialsRequest account
) {
}
