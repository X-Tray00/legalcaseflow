package bg.nbu.legalcaseflow.web.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

public record LawyerRequest(
        @NotBlank String registrationNumber,
        @NotBlank String fullName,
        String specialty,
        @Valid AccountCredentialsRequest account
) {
}
