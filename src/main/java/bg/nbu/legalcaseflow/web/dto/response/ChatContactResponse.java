package bg.nbu.legalcaseflow.web.dto.response;

public record ChatContactResponse(
        Long userId,
        String displayName,
        String role
) {
}
