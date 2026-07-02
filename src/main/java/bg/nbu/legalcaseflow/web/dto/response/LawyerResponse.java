package bg.nbu.legalcaseflow.web.dto.response;

public record LawyerResponse(
        Long id,
        String registrationNumber,
        String fullName,
        String specialty,
        String username
) {
}
