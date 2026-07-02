package bg.nbu.legalcaseflow.service;

import bg.nbu.legalcaseflow.domain.Lawyer;
import bg.nbu.legalcaseflow.domain.AuditAction;
import bg.nbu.legalcaseflow.exception.ConflictException;
import bg.nbu.legalcaseflow.exception.NotFoundException;
import bg.nbu.legalcaseflow.repository.LawyerRepository;
import bg.nbu.legalcaseflow.web.mapper.LawyerMapper;
import bg.nbu.legalcaseflow.web.dto.request.LawyerCreateRequest;
import bg.nbu.legalcaseflow.web.dto.request.LawyerRequest;
import bg.nbu.legalcaseflow.web.dto.response.LawyerResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@Transactional
public class LawyerService {

    private final LawyerRepository lawyerRepository;
    private final AccountProvisioningService accountProvisioningService;
    private final AuditService auditService;
    private final SoftDeleteService softDeleteService;

    public LawyerService(LawyerRepository lawyerRepository,
                         AccountProvisioningService accountProvisioningService,
                         AuditService auditService,
                         SoftDeleteService softDeleteService) {
        this.lawyerRepository = lawyerRepository;
        this.accountProvisioningService = accountProvisioningService;
        this.auditService = auditService;
        this.softDeleteService = softDeleteService;
    }

    @Transactional(readOnly = true)
    public List<LawyerResponse> findAll() {
        return lawyerRepository.findAll().stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public LawyerResponse findById(Long id) {
        return toResponse(get(id));
    }

    public LawyerResponse create(LawyerCreateRequest request) {
        Lawyer lawyer = new Lawyer();
        applyCreate(lawyer, request);
        lawyerRepository.save(lawyer);
        accountProvisioningService.createForLawyer(request.account(), lawyer, false);
        LawyerResponse response = toResponse(lawyer);
        auditService.record(AuditAction.CREATE, "lawyers", lawyer.getId(), null, response, null);
        return response;
    }

    public LawyerResponse update(Long id, LawyerRequest request) {
        Lawyer lawyer = get(id);
        LawyerResponse before = toResponse(lawyer);
        apply(lawyer, request);
        lawyerRepository.save(lawyer);
        accountProvisioningService.createForLawyer(request.account(), lawyer, true);
        LawyerResponse response = toResponse(lawyer);
        auditService.record(AuditAction.UPDATE, "lawyers", id, before, response, null);
        return response;
    }

    public void delete(Long id) {
        Lawyer lawyer = get(id);
        LawyerResponse before = toResponse(lawyer);
        softDeleteService.delete("lawyers", id);
        auditService.record(AuditAction.DELETE, "lawyers", id, before, null, null);
    }

    private Lawyer get(Long id) {
        return lawyerRepository.findById(id).orElseThrow(() -> NotFoundException.of("Lawyer", id));
    }

    private void apply(Lawyer lawyer, LawyerRequest request) {
        lawyer.setRegistrationNumber(request.registrationNumber());
        lawyer.setFullName(request.fullName());
        lawyer.setSpecialty(request.specialty());
    }

    private void applyCreate(Lawyer lawyer, LawyerCreateRequest request) {
        lawyer.setRegistrationNumber(request.registrationNumber());
        lawyer.setFullName(request.fullName());
        lawyer.setSpecialty(request.specialty());
    }

    private LawyerResponse toResponse(Lawyer lawyer) {
        return LawyerMapper.toResponse(lawyer, accountProvisioningService.usernameForLawyer(lawyer.getId()));
    }
}
