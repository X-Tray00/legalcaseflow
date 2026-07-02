package bg.nbu.legalcaseflow.web.dto.response;

public record ClientResponse(
        Long id,
        String fullName,
        String identifier,
        String contact,
        boolean legalAidEligible,
        Long leadLawyerId,
        String leadLawyerName,
        String username
) {
}
