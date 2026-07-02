package bg.nbu.legalcaseflow.web.controller;

import bg.nbu.legalcaseflow.service.LawyerService;
import bg.nbu.legalcaseflow.web.dto.request.LawyerCreateRequest;
import bg.nbu.legalcaseflow.web.dto.request.LawyerRequest;
import bg.nbu.legalcaseflow.web.dto.response.LawyerResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/lawyers")
@Tag(name = "Lawyers", description = "CRUD for lawyers (адвокати)")
public class LawyerController {

    private final LawyerService lawyerService;

    public LawyerController(LawyerService lawyerService) {
        this.lawyerService = lawyerService;
    }

    @GetMapping
    public List<LawyerResponse> findAll() {
        return lawyerService.findAll();
    }

    @GetMapping("/{id}")
    public LawyerResponse findById(@PathVariable Long id) {
        return lawyerService.findById(id);
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<LawyerResponse> create(@Valid @RequestBody LawyerCreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(lawyerService.create(request));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public LawyerResponse update(@PathVariable Long id, @Valid @RequestBody LawyerRequest request) {
        return lawyerService.update(id, request);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        lawyerService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
