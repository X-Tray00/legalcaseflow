package bg.nbu.legalcaseflow.exception;

import java.time.Instant;
import java.util.Map;

/** Consistent error payload returned by {@link GlobalExceptionHandler}. */
public record ApiError(
        Instant timestamp,
        int status,
        String error,
        String message,
        String path,
        Map<String, String> fieldErrors
) {
}
