package bg.nbu.legalcaseflow.service;

import bg.nbu.legalcaseflow.domain.Client;
import bg.nbu.legalcaseflow.domain.AuditAction;
import bg.nbu.legalcaseflow.domain.Document;
import bg.nbu.legalcaseflow.domain.Lawyer;
import bg.nbu.legalcaseflow.domain.User;
import bg.nbu.legalcaseflow.exception.NotFoundException;
import bg.nbu.legalcaseflow.repository.ClientRepository;
import bg.nbu.legalcaseflow.repository.DocumentRepository;
import bg.nbu.legalcaseflow.repository.LawyerRepository;
import bg.nbu.legalcaseflow.web.mapper.DocumentMapper;
import bg.nbu.legalcaseflow.web.dto.request.DocumentRequest;
import bg.nbu.legalcaseflow.web.dto.response.DocumentResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@Transactional
public class DocumentService {

    private final DocumentRepository documentRepository;
    private final ClientRepository clientRepository;
    private final LawyerRepository lawyerRepository;
    private final CurrentUserService currentUserService;
    private final AuditService auditService;
    private final SoftDeleteService softDeleteService;

    public DocumentService(DocumentRepository documentRepository,
                           ClientRepository clientRepository,
                           LawyerRepository lawyerRepository,
                           CurrentUserService currentUserService,
                           AuditService auditService,
                           SoftDeleteService softDeleteService) {
        this.documentRepository = documentRepository;
        this.clientRepository = clientRepository;
        this.lawyerRepository = lawyerRepository;
        this.currentUserService = currentUserService;
        this.auditService = auditService;
        this.softDeleteService = softDeleteService;
    }

    @Transactional(readOnly = true)
    public List<DocumentResponse> findAll() {
        User user = currentUserService.currentUser();
        List<Document> documents = currentUserService.isClient(user)
                ? documentRepository.findByClientId(currentUserService.clientId(user))
                : documentRepository.findAll();
        return documents.stream().map(DocumentMapper::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public DocumentResponse findById(Long id) {
        User user = currentUserService.currentUser();
        Document document = get(id);
        requireCanView(user, document);
        return DocumentMapper.toResponse(document);
    }

    @Transactional(readOnly = true)
    public List<DocumentResponse> findByClientId(Long clientId) {
        User user = currentUserService.currentUser();
        if (currentUserService.isClient(user) && !currentUserService.clientId(user).equals(clientId)) {
            throw new AccessDeniedException("Clients can view only their own documents");
        }
        return documentRepository.findByClientId(clientId).stream().map(DocumentMapper::toResponse).toList();
    }

    public DocumentResponse create(DocumentRequest request) {
        User user = currentUserService.currentUser();
        currentUserService.requireAdminOrLawyer(user);
        Document document = new Document();
        apply(document, request, user);
        DocumentResponse response = DocumentMapper.toResponse(documentRepository.save(document));
        auditService.record(AuditAction.CREATE, "documents", response.id(), null, response, null);
        return response;
    }

    public DocumentResponse update(Long id, DocumentRequest request) {
        User user = currentUserService.currentUser();
        Document document = get(id);
        requireCanEdit(user, document);
        DocumentResponse before = DocumentMapper.toResponse(document);
        apply(document, request, user);
        DocumentResponse response = DocumentMapper.toResponse(documentRepository.save(document));
        auditService.record(AuditAction.UPDATE, "documents", id, before, response, null);
        return response;
    }

    public void delete(Long id) {
        User user = currentUserService.currentUser();
        Document document = get(id);
        requireCanEdit(user, document);
        DocumentResponse before = DocumentMapper.toResponse(document);
        softDeleteService.delete("documents", id);
        auditService.record(AuditAction.DELETE, "documents", id, before, null, null);
    }

    private Document get(Long id) {
        return documentRepository.findById(id).orElseThrow(() -> NotFoundException.of("Document", id));
    }

    private void apply(Document document, DocumentRequest request, User user) {
        Lawyer lawyer = resolveLawyer(request.lawyerId(), user);
        Client client = clientRepository.findById(request.clientId())
                .orElseThrow(() -> NotFoundException.of("Client", request.clientId()));

        document.setTitle(request.title());
        document.setContent(request.content());
        document.setClient(client);
        document.setLawyer(lawyer);
        document.setIssueDate(request.issueDate());
        document.setValidityDays(request.validityDays());
    }

    private Lawyer resolveLawyer(Long requestedLawyerId, User user) {
        if (currentUserService.isLawyer(user)) {
            Long currentLawyerId = currentUserService.lawyerId(user);
            if (!currentLawyerId.equals(requestedLawyerId)) {
                throw new AccessDeniedException("Lawyers can create or update only their own documents");
            }
        }
        return lawyerRepository.findById(requestedLawyerId)
                .orElseThrow(() -> NotFoundException.of("Lawyer", requestedLawyerId));
    }

    private void requireCanView(User user, Document document) {
        if (currentUserService.isClient(user)
                && !currentUserService.clientId(user).equals(document.getClient().getId())) {
            throw new AccessDeniedException("Clients can view only their own documents");
        }
    }

    private void requireCanEdit(User user, Document document) {
        if (currentUserService.isAdmin(user)) {
            return;
        }
        if (currentUserService.isLawyer(user)
                && currentUserService.lawyerId(user).equals(document.getLawyer().getId())) {
            return;
        }
        throw new AccessDeniedException("Lawyers can edit only documents they issued");
    }
}
