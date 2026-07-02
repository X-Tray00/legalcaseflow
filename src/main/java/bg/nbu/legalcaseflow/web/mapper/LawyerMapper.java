package bg.nbu.legalcaseflow.web.mapper;

import bg.nbu.legalcaseflow.domain.Lawyer;
import bg.nbu.legalcaseflow.web.dto.response.LawyerResponse;

public final class LawyerMapper {

    private LawyerMapper() {
    }

    public static LawyerResponse toResponse(Lawyer lawyer) {
        return toResponse(lawyer, null);
    }

    public static LawyerResponse toResponse(Lawyer lawyer, String username) {
        return new LawyerResponse(
                lawyer.getId(),
                lawyer.getRegistrationNumber(),
                lawyer.getFullName(),
                lawyer.getSpecialty(),
                username
        );
    }
}
