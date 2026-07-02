package bg.nbu.legalcaseflow.web.controller;

import bg.nbu.legalcaseflow.service.ClientService;
import bg.nbu.legalcaseflow.web.dto.request.ClientCreateRequest;
import bg.nbu.legalcaseflow.web.dto.request.ClientRequest;
import bg.nbu.legalcaseflow.web.dto.response.ClientResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/clients")
@Tag(name = "Clients", description = "CRUD for clients (доверители)")
public class ClientController {

    private final ClientService clientService;

    public ClientController(ClientService clientService) {
        this.clientService = clientService;
    }

    @GetMapping
    public List<ClientResponse> findAll() {
        return clientService.findAll();
    }

    @GetMapping("/{id}")
    public ClientResponse findById(@PathVariable Long id) {
        return clientService.findById(id);
    }

    // този метод се използва за да се създаде нов клиент.
    // предварително проверяваме дали роли ADMIN или LAWYER имат достъп.
    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN','LAWYER')")
    public ResponseEntity<ClientResponse> create(@Valid @RequestBody ClientCreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(clientService.create(request));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','LAWYER')")
    public ClientResponse update(@PathVariable Long id, @Valid @RequestBody ClientRequest request) {
        return clientService.update(id, request);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','LAWYER')")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        clientService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
