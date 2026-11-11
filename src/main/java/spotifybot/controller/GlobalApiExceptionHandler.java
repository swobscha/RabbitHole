package spotifybot.controller;

import io.sentry.Sentry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import spotifybot.model.ApiErrorResponse;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.concurrent.TimeoutException;

@RestControllerAdvice
public class GlobalApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalApiExceptionHandler.class);

    @ExceptionHandler(ResponseStatusException.class)
    public Mono<ResponseEntity<ApiErrorResponse>> handleResponseStatusException(
            ResponseStatusException error,
            ServerWebExchange exchange
    ) {
        HttpStatusCode statusCode = error.getStatusCode();

        String message = error.getReason();

        if (message == null || message.isBlank()) {
            message = defaultMessageForStatus(statusCode);
        }

        return Mono.just(ResponseEntity
                .status(statusCode)
                .body(buildError(statusCode, message, exchange)));
    }

    @ExceptionHandler(WebClientResponseException.class)
    public Mono<ResponseEntity<ApiErrorResponse>> handleWebClientResponseException(
            WebClientResponseException error,
            ServerWebExchange exchange
    ) {
        HttpStatusCode statusCode = error.getStatusCode();

        String message = "An external music service returned an error.";

        if (statusCode.value() == 429) {
            String retryAfter = error.getHeaders().getFirst("Retry-After");

            if (retryAfter != null && !retryAfter.isBlank()) {
                message = "A music service is rate-limiting requests. Wait about "
                        + retryAfter
                        + " seconds and try again.";
            } else {
                message = "A music service is rate-limiting requests. Wait a bit and try again.";
            }
        }

        return Mono.just(ResponseEntity
                .status(statusCode)
                .body(buildError(statusCode, message, exchange)));
    }

    @ExceptionHandler({TimeoutException.class, WebClientRequestException.class})
    public Mono<ResponseEntity<ApiErrorResponse>> handleUnreachableService(
            Exception error,
            ServerWebExchange exchange
    ) {
        HttpStatus status = HttpStatus.SERVICE_UNAVAILABLE;

        return Mono.just(ResponseEntity
                .status(status)
                .body(buildError(
                        status,
                        "A music service could not be reached. Try again in a moment.",
                        exchange
                )));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public Mono<ResponseEntity<ApiErrorResponse>> handleIllegalArgumentException(
            IllegalArgumentException error,
            ServerWebExchange exchange
    ) {
        HttpStatus status = HttpStatus.BAD_REQUEST;

        String message = error.getMessage();

        if (message == null || message.isBlank()) {
            message = "The request was invalid.";
        }

        return Mono.just(ResponseEntity
                .status(status)
                .body(buildError(status, message, exchange)));
    }

    @ExceptionHandler(Exception.class)
    public Mono<ResponseEntity<ApiErrorResponse>> handleGenericException(
            Exception error,
            ServerWebExchange exchange
    ) {
        HttpStatus status = HttpStatus.INTERNAL_SERVER_ERROR;

        log.error("Unhandled error on {}", exchange.getRequest().getPath().value(), error);

        // Handled here, so Sentry would not see it otherwise. Does nothing unless SENTRY_DSN is set.
        Sentry.captureException(error);

        return Mono.just(ResponseEntity
                .status(status)
                .body(buildError(
                        status,
                        "Something went wrong on our side. Please try again.",
                        exchange
                )));
    }

    private ApiErrorResponse buildError(
            HttpStatusCode statusCode,
            String message,
            ServerWebExchange exchange
    ) {
        return new ApiErrorResponse(
                statusCode.value(),
                reasonPhrase(statusCode),
                message,
                exchange.getRequest().getPath().value(),
                OffsetDateTime.now(ZoneOffset.UTC).toString()
        );
    }

    private String reasonPhrase(HttpStatusCode statusCode) {
        if (statusCode instanceof HttpStatus httpStatus) {
            return httpStatus.getReasonPhrase();
        }

        return statusCode.toString();
    }

    private String defaultMessageForStatus(HttpStatusCode statusCode) {
        if (statusCode.value() == 401) {
            return "Spotify is not connected. Connect Spotify and try again.";
        }

        if (statusCode.value() == 429) {
            return "Spotify is rate-limiting requests. Wait a bit and try again.";
        }

        if (statusCode.value() >= 500) {
            return "Something went wrong on the backend.";
        }

        return "The request could not be completed.";
    }
}