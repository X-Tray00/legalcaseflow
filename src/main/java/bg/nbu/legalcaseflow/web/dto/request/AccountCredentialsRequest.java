package bg.nbu.legalcaseflow.web.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record AccountCredentialsRequest(
        @NotBlank
        @Size(min = 3, max = 50)
        @Pattern(regexp = "^[A-Za-z0-9._-]+$",
                message = "Username may contain only letters, digits and . _ -")
        String username,
        @NotBlank @Size(min = 8, max = 100) String password
) {
}
