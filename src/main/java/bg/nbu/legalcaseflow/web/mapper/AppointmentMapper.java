package bg.nbu.legalcaseflow.web.mapper;

import bg.nbu.legalcaseflow.domain.Appointment;
import bg.nbu.legalcaseflow.domain.Client;
import bg.nbu.legalcaseflow.domain.Lawyer;
import bg.nbu.legalcaseflow.web.dto.response.AppointmentResponse;

public final class AppointmentMapper {

    private AppointmentMapper() {
    }

    public static AppointmentResponse toResponse(Appointment appointment) {
        Client client = appointment.getClient();
        Lawyer lawyer = appointment.getLawyer();
        return new AppointmentResponse(
                appointment.getId(),
                client.getId(),
                client.getFullName(),
                lawyer.getId(),
                lawyer.getFullName(),
                appointment.getScheduledAt(),
                appointment.getStatus(),
                appointment.getTopic(),
                appointment.getNotes()
        );
    }
}

