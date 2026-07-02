package bg.nbu.legalcaseflow.web.dto.response;

public record AuthResponse(
        String token,
        String username,
        String role,
        Long lawyerId,
        Long clientId
) {
}
