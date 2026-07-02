package bg.nbu.legalcaseflow.service;

import bg.nbu.legalcaseflow.domain.*;
import bg.nbu.legalcaseflow.exception.NotFoundException;
import bg.nbu.legalcaseflow.repository.AppointmentRepository;
import bg.nbu.legalcaseflow.repository.ClientRepository;
import bg.nbu.legalcaseflow.repository.LawyerRepository;
import bg.nbu.legalcaseflow.web.mapper.AppointmentMapper;
import bg.nbu.legalcaseflow.web.dto.request.AppointmentRequest;
import bg.nbu.legalcaseflow.web.dto.response.AppointmentResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@Transactional
public class AppointmentService {

    private final AppointmentRepository appointmentRepository;
    private final ClientRepository clientRepository;
    private final LawyerRepository lawyerRepository;
    private final CurrentUserService currentUserService;
    private final AuditService auditService;
    private final SoftDeleteService softDeleteService;

    public AppointmentService(AppointmentRepository appointmentRepository,
                              ClientRepository clientRepository,
                              LawyerRepository lawyerRepository,
                              CurrentUserService currentUserService,
                              AuditService auditService,
                              SoftDeleteService softDeleteService) {
        this.appointmentRepository = appointmentRepository;
        this.clientRepository = clientRepository;
        this.lawyerRepository = lawyerRepository;
        this.currentUserService = currentUserService;
        this.auditService = auditService;
        this.softDeleteService = softDeleteService;
    }

    @Transactional(readOnly = true)
    public List<AppointmentResponse> findAll() {
        User user = currentUserService.currentUser();
        List<Appointment> appointments;
        if (currentUserService.isClient(user)) {
            appointments = appointmentRepository.findByClientIdOrderByScheduledAtDesc(currentUserService.clientId(user));
        } else if (currentUserService.isLawyer(user)) {
            appointments = appointmentRepository.findByLawyerIdOrderByScheduledAtDesc(currentUserService.lawyerId(user));
        } else {
            appointments = appointmentRepository.findAllByOrderByScheduledAtDesc();
        }
        return appointments.stream().map(AppointmentMapper::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public AppointmentResponse findById(Long id) {
        User user = currentUserService.currentUser();
        Appointment appointment = get(id);
        requireCanView(user, appointment);
        return AppointmentMapper.toResponse(appointment);
    }

    public AppointmentResponse create(AppointmentRequest request) {
        User user = currentUserService.currentUser();
        Appointment appointment = new Appointment();
        apply(appointment, request, user);
        AppointmentResponse response = AppointmentMapper.toResponse(appointmentRepository.save(appointment));
        auditService.record(AuditAction.CREATE, "appointments", response.id(), null, response, null);
        return response;
    }

    public AppointmentResponse update(Long id, AppointmentRequest request) {
        User user = currentUserService.currentUser();
        Appointment appointment = get(id);
        requireCanEdit(user, appointment, request);
        AppointmentResponse before = AppointmentMapper.toResponse(appointment);
        apply(appointment, request, user);
        AppointmentResponse response = AppointmentMapper.toResponse(appointmentRepository.save(appointment));
        auditService.record(AuditAction.UPDATE, "appointments", id, before, response, null);
        return response;
    }

    public void delete(Long id) {
        User user = currentUserService.currentUser();
        Appointment appointment = get(id);
        requireCanDelete(user, appointment);
        AppointmentResponse before = AppointmentMapper.toResponse(appointment);
        softDeleteService.delete("appointments", id);
        auditService.record(AuditAction.DELETE, "appointments", id, before, null, null);
    }

    private Appointment get(Long id) {
        return appointmentRepository.findById(id).orElseThrow(() -> NotFoundException.of("Appointment", id));
    }

    private void apply(Appointment appointment, AppointmentRequest request, User user) {
        Client client = resolveClient(request.clientId(), user);
        Lawyer lawyer = resolveLawyer(request.lawyerId(), user);
        AppointmentStatus status = request.status() == null ? AppointmentStatus.REQUESTED : request.status();
        if (currentUserService.isClient(user) && status != AppointmentStatus.REQUESTED && status != AppointmentStatus.CANCELLED) {
            throw new AccessDeniedException("Clients can request or cancel appointments only");
        }
        ensureLawyerIsAvailable(appointment.getId(), lawyer.getId(), request.scheduledAt(), status);

        appointment.setClient(client);
        appointment.setLawyer(lawyer);
        appointment.setScheduledAt(request.scheduledAt());
        appointment.setStatus(status);
        appointment.setTopic(request.topic());
        appointment.setNotes(request.notes());
    }

    private Client resolveClient(Long requestedClientId, User user) {
        if (currentUserService.isClient(user) && !currentUserService.clientId(user).equals(requestedClientId)) {
            throw new AccessDeniedException("Clients can book appointments only for themselves");
        }
        return clientRepository.findById(requestedClientId)
                .orElseThrow(() -> NotFoundException.of("Client", requestedClientId));
    }

    private Lawyer resolveLawyer(Long requestedLawyerId, User user) {
        if (currentUserService.isLawyer(user) && !currentUserService.lawyerId(user).equals(requestedLawyerId)) {
            throw new AccessDeniedException("Lawyers can manage only their own appointments");
        }
        return lawyerRepository.findById(requestedLawyerId)
                .orElseThrow(() -> NotFoundException.of("Lawyer", requestedLawyerId));
    }

    private void ensureLawyerIsAvailable(Long currentAppointmentId, Long lawyerId,
                                         java.time.LocalDateTime scheduledAt, AppointmentStatus status) {
        if (status == AppointmentStatus.CANCELLED) {
            return;
        }
        boolean conflict = appointmentRepository.findByLawyerIdAndScheduledAt(lawyerId, scheduledAt).stream()
                .anyMatch(existing -> !existing.getId().equals(currentAppointmentId)
                        && existing.getStatus() != AppointmentStatus.CANCELLED);
        if (conflict) {
            throw new IllegalArgumentException("Lawyer already has an appointment at this time");
        }
    }

    private void requireCanView(User user, Appointment appointment) {
        if (currentUserService.isAdmin(user)) {
            return;
        }
        if (currentUserService.isLawyer(user)
                && currentUserService.lawyerId(user).equals(appointment.getLawyer().getId())) {
            return;
        }
        if (currentUserService.isClient(user)
                && currentUserService.clientId(user).equals(appointment.getClient().getId())) {
            return;
        }
        throw new AccessDeniedException("Cannot view this appointment");
    }

    private void requireCanEdit(User user, Appointment appointment, AppointmentRequest request) {
        requireCanView(user, appointment);
        if (currentUserService.isClient(user) && request.status() != AppointmentStatus.CANCELLED) {
            throw new AccessDeniedException("Clients can only cancel their appointments");
        }
    }

    private void requireCanDelete(User user, Appointment appointment) {
        requireCanView(user, appointment);
        if (currentUserService.isClient(user) && appointment.getStatus() == AppointmentStatus.COMPLETED) {
            throw new AccessDeniedException("Completed appointments cannot be deleted by clients");
        }
    }
}
