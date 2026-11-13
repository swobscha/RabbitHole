package rabbithole.model;

public record ApiErrorResponse(
        int status,
        String error,
        String message,
        String path,
        String timestamp
) {
}