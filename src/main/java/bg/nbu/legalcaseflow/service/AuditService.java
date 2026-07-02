package bg.nbu.legalcaseflow.service;

import bg.nbu.legalcaseflow.domain.*;
import bg.nbu.legalcaseflow.repository.AuditEventRepository;
import bg.nbu.legalcaseflow.repository.UserRepository;
import bg.nbu.legalcaseflow.websocket.AppChangeEventPublisher;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.Instant;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

@Service
public class AuditService {

    private static final Set<String> SENSITIVE_FIELDS = Set.of("password", "token", "authorization");

    private final AuditEventRepository auditEventRepository;
    private final UserRepository userRepository;
    private final ObjectMapper objectMapper;
    private final AppChangeEventPublisher appChangeEventPublisher;

    public AuditService(AuditEventRepository auditEventRepository,
                        UserRepository userRepository,
                        ObjectMapper objectMapper,
                        AppChangeEventPublisher appChangeEventPublisher) {
        this.auditEventRepository = auditEventRepository;
        this.userRepository = userRepository;
        this.objectMapper = objectMapper;
        this.appChangeEventPublisher = appChangeEventPublisher;
    }

    @Transactional
    public AuditEvent record(AuditAction action, String resourceType, Long resourceId,
                             Object beforeState, Object afterState, Object metadata) {
        return save(action, AuditOutcome.SUCCESS, resourceType, resourceId,
                beforeState, afterState, metadata, null, null);
    }

    @Transactional
    public AuditEvent recordRestore(AuditEvent source, Object beforeState, Object afterState) {
        return save(AuditAction.RESTORE, AuditOutcome.SUCCESS, source.getResourceType(), source.getResourceId(),
                beforeState, afterState, Map.of("sourceAuditEventId", source.getId()), source.getId(), null);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public AuditEvent recordSecurity(AuditAction action, AuditOutcome outcome, String actorUsername, Object metadata) {
        // COURSEWORK: login failure няма бизнес транзакция, затова security audit се записва
        // в независима REQUIRES_NEW транзакция и не се губи при authentication exception.
        return save(action, outcome, "security", null, null, null, metadata, null, actorUsername);
    }

    private AuditEvent save(AuditAction action, AuditOutcome outcome, String resourceType, Long resourceId,
                            Object beforeState, Object afterState, Object metadata,
                            Long sourceAuditEventId, String explicitActorUsername) {
        AuditEvent event = new AuditEvent();
        event.setOccurredAt(Instant.now());
        event.setAction(action);
        event.setOutcome(outcome);
        event.setResourceType(resourceType);
        event.setResourceId(resourceId);
        event.setBeforeState(json(beforeState));
        event.setAfterState(json(afterState));
        event.setMetadata(json(metadata));
        event.setSourceAuditEventId(sourceAuditEventId);
        populateActor(event, explicitActorUsername);
        populateRequest(event);
        AuditEvent saved = auditEventRepository.save(event);
        publishAfterCommit();
        return saved;
    }

    private void populateActor(AuditEvent event, String explicitActorUsername) {
        String username = explicitActorUsername;
        if (username == null) {
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            if (authentication != null && authentication.isAuthenticated()
                    && !"anonymousUser".equals(authentication.getName())) {
                username = authentication.getName();
            }
        }
        if (username == null || username.isBlank()) {
            return;
        }
        event.setActorUsername(username);
        userRepository.findByUsername(username).ifPresent(user -> {
            event.setActorId(user.getId());
            event.setActorRole(user.getRole());
        });
    }

    private void populateRequest(AuditEvent event) {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes)) {
            return;
        }
        HttpServletRequest request = attributes.getRequest();
        event.setRequestMethod(request.getMethod());
        event.setRequestPath(request.getRequestURI());
        event.setIpAddress(clientIp(request));
        event.setUserAgent(trim(request.getHeader("User-Agent"), 1000));
    }

    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return trim(forwarded.split(",")[0].trim(), 255);
        }
        return trim(request.getRemoteAddr(), 255);
    }

    private String trim(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }

    private String json(Object value) {
        if (value == null) {
            return null;
        }
        try {
            JsonNode node = objectMapper.valueToTree(value);
            // COURSEWORK: snapshot-ите са JSON в TEXT колони, но чувствителни полета се
            // премахват рекурсивно преди persistence.
            sanitize(node);
            return objectMapper.writeValueAsString(node);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Cannot serialize audit snapshot", ex);
        }
    }

    private void sanitize(JsonNode node) {
        if (node instanceof ObjectNode objectNode) {
            Iterator<String> names = objectNode.fieldNames();
            while (names.hasNext()) {
                String name = names.next();
                if (SENSITIVE_FIELDS.contains(name.toLowerCase())) {
                    names.remove();
                }
            }
            objectNode.elements().forEachRemaining(this::sanitize);
        } else if (node.isArray()) {
            node.elements().forEachRemaining(this::sanitize);
        }
    }

    private void publishAfterCommit() {
        Runnable publish = () -> appChangeEventPublisher.publishToAdmins("audit-events", "CREATED");
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    publish.run();
                }
            });
        } else {
            publish.run();
        }
    }
}
