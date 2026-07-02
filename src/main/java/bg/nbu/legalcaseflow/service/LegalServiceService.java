package bg.nbu.legalcaseflow.service;

import bg.nbu.legalcaseflow.domain.*;
import bg.nbu.legalcaseflow.exception.NotFoundException;
import bg.nbu.legalcaseflow.repository.CaseTypeRepository;
import bg.nbu.legalcaseflow.repository.ClientRepository;
import bg.nbu.legalcaseflow.repository.LawyerRepository;
import bg.nbu.legalcaseflow.repository.LegalServiceRepository;
import bg.nbu.legalcaseflow.web.mapper.LegalServiceMapper;
import bg.nbu.legalcaseflow.web.dto.request.LegalServiceRequest;
import bg.nbu.legalcaseflow.web.dto.response.LegalServiceResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@Transactional
public class LegalServiceService {

    private final LegalServiceRepository legalServiceRepository;
    private final LawyerRepository lawyerRepository;
    private final ClientRepository clientRepository;
    private final CaseTypeRepository caseTypeRepository;
    private final CurrentUserService currentUserService;
    private final AuditService auditService;
    private final SoftDeleteService softDeleteService;

    public LegalServiceService(LegalServiceRepository legalServiceRepository,
                               LawyerRepository lawyerRepository,
                               ClientRepository clientRepository,
                               CaseTypeRepository caseTypeRepository,
                               CurrentUserService currentUserService,
                               AuditService auditService,
                               SoftDeleteService softDeleteService) {
        this.legalServiceRepository = legalServiceRepository;
        this.lawyerRepository = lawyerRepository;
        this.clientRepository = clientRepository;
        this.caseTypeRepository = caseTypeRepository;
        this.currentUserService = currentUserService;
        this.auditService = auditService;
        this.softDeleteService = softDeleteService;
    }

    @Transactional(readOnly = true)
    public List<LegalServiceResponse> findAll() {
        User user = currentUserService.currentUser();
        // authorization е и на ниво данни, не само endpoint. CLIENT получава
        // SQL резултат само за своя profile ID, вместо всички записи да се филтрират в UI.
        List<LegalService> services = currentUserService.isClient(user)
                ? legalServiceRepository.findByClientIdOrderByDateDesc(currentUserService.clientId(user))
                : legalServiceRepository.findAllByOrderByDateDesc();
        return services.stream().map(LegalServiceMapper::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public LegalServiceResponse findById(Long id) {
        User user = currentUserService.currentUser();
        LegalService service = get(id);
        requireCanView(user, service);
        return LegalServiceMapper.toResponse(service);
    }

    @Transactional(readOnly = true)
    public List<LegalServiceResponse> findByClientId(Long clientId) {
        User user = currentUserService.currentUser();
        if (currentUserService.isClient(user) && !currentUserService.clientId(user).equals(clientId)) {
            throw new AccessDeniedException("Clients can view only their own services");
        }
        return legalServiceRepository.findByClientIdOrderByDateDesc(clientId).stream()
                .map(LegalServiceMapper::toResponse)
                .toList();
    }

    public LegalServiceResponse create(LegalServiceRequest request) {
        User user = currentUserService.currentUser();
        currentUserService.requireAdminOrLawyer(user);
        LegalService service = new LegalService();
        apply(service, request, user);
        LegalServiceResponse response = LegalServiceMapper.toResponse(legalServiceRepository.save(service));
        auditService.record(AuditAction.CREATE, "legal-services", response.id(), null, response, null);
        return response;
    }

    public LegalServiceResponse update(Long id, LegalServiceRequest request) {
        User user = currentUserService.currentUser();
        LegalService service = get(id);
        requireCanEdit(user, service);
        LegalServiceResponse before = LegalServiceMapper.toResponse(service);
        apply(service, request, user);
        LegalServiceResponse response = LegalServiceMapper.toResponse(legalServiceRepository.save(service));
        auditService.record(AuditAction.UPDATE, "legal-services", id, before, response, null);
        return response;
    }

    public void delete(Long id) {
        User user = currentUserService.currentUser();
        LegalService service = get(id);
        requireCanEdit(user, service);
        LegalServiceResponse before = LegalServiceMapper.toResponse(service);
        softDeleteService.delete("legal-services", id);
        auditService.record(AuditAction.DELETE, "legal-services", id, before, null, null);
    }

    private LegalService get(Long id) {
        return legalServiceRepository.findById(id).orElseThrow(() -> NotFoundException.of("LegalService", id));
    }

    private void apply(LegalService service, LegalServiceRequest request, User user) {
        // ако адвокат опитва да впише чужд адвокат му хвърля отказ (403).
        Lawyer lawyer = resolveLawyer(request.lawyerId(), user);
        Client client = clientRepository.findById(request.clientId())
                .orElseThrow(() -> NotFoundException.of("Client", request.clientId()));
        CaseType caseType = caseTypeRepository.findById(request.caseTypeId())
                .orElseThrow(() -> NotFoundException.of("CaseType", request.caseTypeId()));

        // условното правило от заданието е Payer.NBPP при право на правна помощ,
        // иначе Payer.CLIENT. Пазим snapshot и го преизчисляваме само при смяна на доверителя.
        boolean clientChanged = service.getClient() == null
                || !service.getClient().getId().equals(client.getId());
        // попълваме полетата на услугата, за да се запази консистентността на данните.
        // проверяваме дали платецът е сменен, ако да преизчисляваме платеца.
        service.setDate(request.date());
        service.setLawyer(lawyer);
        service.setClient(client);
        service.setCaseType(caseType);
        service.setDescription(request.description());
        service.setFee(request.fee());
        if (service.getId() == null) {
            service.setPaid(false);
        }
        if (service.getPayer() == null || clientChanged) {
            service.setPayer(client.isLegalAidEligible() ? Payer.NBPP : Payer.CLIENT);
        }
    }

    // ако адвокат опитва да впише чужд адвокат му връща отказ (403).
    private Lawyer resolveLawyer(Long requestedLawyerId, User user) {
        if (currentUserService.isLawyer(user)) {
            Long currentLawyerId = currentUserService.lawyerId(user);
            if (!currentLawyerId.equals(requestedLawyerId)) {
                throw new AccessDeniedException("Lawyers can create or update only their own services");
            }
        }
        return lawyerRepository.findById(requestedLawyerId)
                .orElseThrow(() -> NotFoundException.of("Lawyer", requestedLawyerId));
    }

    private void requireCanView(User user, LegalService service) {
        if (currentUserService.isClient(user)
                && !currentUserService.clientId(user).equals(service.getClient().getId())) {
            throw new AccessDeniedException("Clients can view only their own services");
        }
    }

    private void requireCanEdit(User user, LegalService service) {
        if (currentUserService.isAdmin(user)) {
            return;
        }
        if (currentUserService.isLawyer(user)
                && currentUserService.lawyerId(user).equals(service.getLawyer().getId())) {
            return;
        }
        throw new AccessDeniedException("Lawyers can edit only services they performed");
    }
}
