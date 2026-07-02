package bg.nbu.legalcaseflow.service;

import bg.nbu.legalcaseflow.domain.Client;
import bg.nbu.legalcaseflow.domain.AuditAction;
import bg.nbu.legalcaseflow.domain.User;
import bg.nbu.legalcaseflow.exception.ConflictException;
import bg.nbu.legalcaseflow.exception.NotFoundException;
import bg.nbu.legalcaseflow.repository.ClientRepository;
import bg.nbu.legalcaseflow.repository.LawyerRepository;
import bg.nbu.legalcaseflow.web.mapper.ClientMapper;
import bg.nbu.legalcaseflow.web.dto.request.ClientCreateRequest;
import bg.nbu.legalcaseflow.web.dto.request.ClientRequest;
import bg.nbu.legalcaseflow.web.dto.response.ClientResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@Transactional
public class ClientService {

    private final ClientRepository clientRepository;
    private final LawyerRepository lawyerRepository;
    private final CurrentUserService currentUserService;
    private final AccountProvisioningService accountProvisioningService;
    private final AuditService auditService;
    private final SoftDeleteService softDeleteService;

    public ClientService(ClientRepository clientRepository,
                         LawyerRepository lawyerRepository,
                         CurrentUserService currentUserService,
                         AccountProvisioningService accountProvisioningService,
                         AuditService auditService,
                         SoftDeleteService softDeleteService) {
        this.clientRepository = clientRepository;
        this.lawyerRepository = lawyerRepository;
        this.currentUserService = currentUserService;
        this.accountProvisioningService = accountProvisioningService;
        this.auditService = auditService;
        this.softDeleteService = softDeleteService;
    }

    @Transactional(readOnly = true)
    public List<ClientResponse> findAll() {
        User user = currentUserService.currentUser();
        List<Client> clients = currentUserService.isClient(user)
                ? List.of(get(currentUserService.clientId(user)))
                : clientRepository.findAll();
        return clients.stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public ClientResponse findById(Long id) {
        User user = currentUserService.currentUser();
        if (currentUserService.isClient(user) && !currentUserService.clientId(user).equals(id)) {
            throw new AccessDeniedException("Clients can view only their own profile");
        }
        return toResponse(get(id));
    }

    public ClientResponse create(ClientCreateRequest request) {
        User actor = currentUserService.currentUser();
        currentUserService.requireAdminOrLawyer(actor);
        Client client = new Client();
        applyCreate(client, request, actor);
        clientRepository.save(client);
        // class-level @Transactional прави profile + optional login една операция.
        // Ако username е зает, rollback премахва и току-що създадения Client.
        accountProvisioningService.createForClient(request.account(), client, false);
        ClientResponse response = toResponse(client);
        auditService.record(AuditAction.CREATE, "clients", client.getId(), null, response, null);
        return response;
    }

    public ClientResponse update(Long id, ClientRequest request) {
        User actor = currentUserService.currentUser();
        currentUserService.requireAdminOrLawyer(actor);
        Client client = get(id);
        ClientResponse before = toResponse(client);
        apply(client, request);
        clientRepository.save(client);
        accountProvisioningService.createForClient(request.account(), client, currentUserService.isAdmin(actor));
        ClientResponse response = toResponse(client);
        auditService.record(AuditAction.UPDATE, "clients", id, before, response, null);
        return response;
    }

    public void delete(Long id) {
        currentUserService.requireAdminOrLawyer(currentUserService.currentUser());
        Client client = get(id);
        ClientResponse before = toResponse(client);
        softDeleteService.delete("clients", id);
        auditService.record(AuditAction.DELETE, "clients", id, before, null, null);
    }

    private Client get(Long id) {
        return clientRepository.findById(id).orElseThrow(() -> NotFoundException.of("Client", id));
    }

    private void apply(Client client, ClientRequest request) {
        client.setFullName(request.fullName());
        client.setIdentifier(request.identifier());
        client.setContact(request.contact());
        client.setLegalAidEligible(request.legalAidEligible());
        if (request.leadLawyerId() != null) {
            client.setLeadLawyer(lawyerRepository.findById(request.leadLawyerId())
                    .orElseThrow(() -> NotFoundException.of("Lawyer", request.leadLawyerId())));
        } else {
            client.setLeadLawyer(null);
        }
    }

    private void applyCreate(Client client, ClientCreateRequest request, User actor) {
        client.setFullName(request.fullName());
        client.setIdentifier(request.identifier());
        client.setContact(request.contact());
        client.setLegalAidEligible(request.legalAidEligible());
        // проверяваме дали текущият акаунт е адвокат.
        // ако е адвокат, проверяваме дали leadLawyerId от request-а е същия като текущият адвокат.
        // ако е същия, проверяваме дали leadLawyerId от request-а е валиден адвокат.
        // ако е валиден адвокат, проверяваме дали leadLawyerId от request-а е валиден адвокат.
        // ако е валиден адвокат, проверяваме дали leadLawyerId от request-а е валиден адвокат.
        if (currentUserService.isLawyer(actor)) {
            // адвокатът не може да подмени leadLawyerId от request-а със свой избор.
            Long lawyerId = currentUserService.lawyerId(actor);
            client.setLeadLawyer(lawyerRepository.findById(lawyerId)
                    .orElseThrow(() -> NotFoundException.of("Lawyer", lawyerId)));
        } else if (request.leadLawyerId() != null) {
            client.setLeadLawyer(lawyerRepository.findById(request.leadLawyerId())
                    .orElseThrow(() -> NotFoundException.of("Lawyer", request.leadLawyerId())));
        } else {
            client.setLeadLawyer(null);
        }
    }

    private ClientResponse toResponse(Client client) {
        return ClientMapper.toResponse(client, accountProvisioningService.usernameForClient(client.getId()));
    }
}
