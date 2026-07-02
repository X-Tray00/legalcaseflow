package bg.nbu.legalcaseflow.service;

import bg.nbu.legalcaseflow.domain.CaseType;
import bg.nbu.legalcaseflow.domain.AuditAction;
import bg.nbu.legalcaseflow.exception.NotFoundException;
import bg.nbu.legalcaseflow.repository.CaseTypeRepository;
import bg.nbu.legalcaseflow.web.mapper.CaseTypeMapper;
import bg.nbu.legalcaseflow.web.dto.request.CaseTypeRequest;
import bg.nbu.legalcaseflow.web.dto.response.CaseTypeResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@Transactional
public class CaseTypeService {

    private final CaseTypeRepository caseTypeRepository;
    private final AuditService auditService;
    private final SoftDeleteService softDeleteService;

    public CaseTypeService(CaseTypeRepository caseTypeRepository, AuditService auditService,
                           SoftDeleteService softDeleteService) {
        this.caseTypeRepository = caseTypeRepository;
        this.auditService = auditService;
        this.softDeleteService = softDeleteService;
    }

    @Transactional(readOnly = true)
    public List<CaseTypeResponse> findAll() {
        return caseTypeRepository.findAll().stream().map(CaseTypeMapper::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public CaseTypeResponse findById(Long id) {
        return CaseTypeMapper.toResponse(get(id));
    }

    public CaseTypeResponse create(CaseTypeRequest request) {
        CaseType caseType = new CaseType();
        apply(caseType, request);
        CaseTypeResponse response = CaseTypeMapper.toResponse(caseTypeRepository.save(caseType));
        auditService.record(AuditAction.CREATE, "case-types", response.id(), null, response, null);
        return response;
    }

    public CaseTypeResponse update(Long id, CaseTypeRequest request) {
        CaseType caseType = get(id);
        CaseTypeResponse before = CaseTypeMapper.toResponse(caseType);
        apply(caseType, request);
        CaseTypeResponse response = CaseTypeMapper.toResponse(caseTypeRepository.save(caseType));
        auditService.record(AuditAction.UPDATE, "case-types", id, before, response, null);
        return response;
    }

    public void delete(Long id) {
        CaseTypeResponse before = CaseTypeMapper.toResponse(get(id));
        softDeleteService.delete("case-types", id);
        auditService.record(AuditAction.DELETE, "case-types", id, before, null, null);
    }

    private CaseType get(Long id) {
        return caseTypeRepository.findById(id).orElseThrow(() -> NotFoundException.of("CaseType", id));
    }

    private void apply(CaseType caseType, CaseTypeRequest request) {
        caseType.setName(request.name());
        caseType.setDescription(request.description());
    }
}
