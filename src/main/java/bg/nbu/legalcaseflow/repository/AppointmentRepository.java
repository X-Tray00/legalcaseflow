package bg.nbu.legalcaseflow.repository;

import bg.nbu.legalcaseflow.domain.Appointment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

public interface AppointmentRepository extends JpaRepository<Appointment, Long> {

    List<Appointment> findAllByOrderByScheduledAtDesc();

    List<Appointment> findByClientIdOrderByScheduledAtDesc(Long clientId);

    List<Appointment> findByLawyerIdOrderByScheduledAtDesc(Long lawyerId);

    List<Appointment> findByLawyerIdAndScheduledAt(Long lawyerId, LocalDateTime scheduledAt);
}

