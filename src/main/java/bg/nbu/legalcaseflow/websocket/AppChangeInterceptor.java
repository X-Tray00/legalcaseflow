package bg.nbu.legalcaseflow.websocket;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Map;
import java.util.Set;

@Component
public class AppChangeInterceptor implements HandlerInterceptor {

    private static final Set<String> MUTATING_METHODS = Set.of("POST", "PUT", "PATCH", "DELETE");
    private static final Map<String, String> ACTIONS = Map.of(
            "POST", "CREATED",
            "PUT", "UPDATED",
            "PATCH", "UPDATED",
            "DELETE", "DELETED"
    );
    private static final Set<String> TRACKED_RESOURCES = Set.of(
            "clients",
            "lawyers",
            "case-types",
            "legal-services",
            "documents",
            "appointments",
            "invoices"
    );

    private final AppChangeEventPublisher eventPublisher;

    public AppChangeInterceptor(AppChangeEventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        // UI се обновява event-driven само след успешна mutating REST заявка.
        // GET операциите не излъчват събития и приложението не използва polling.
        if (ex != null || response.getStatus() < 200 || response.getStatus() >= 300
                || !MUTATING_METHODS.contains(request.getMethod())) {
            return;
        }

        String[] segments = request.getRequestURI().split("/");
        if (segments.length < 3 || !"api".equals(segments[1]) || !TRACKED_RESOURCES.contains(segments[2])) {
            return;
        }

        eventPublisher.publish(segments[2], ACTIONS.get(request.getMethod()));
    }
}
