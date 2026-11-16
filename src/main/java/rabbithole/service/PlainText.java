package rabbithole.service;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Text visitors type (artist and playlist names, filters) ends up in the logs. A line break in it
 * could add a fake line to them, so text with line breaks or other control characters is refused.
 */
public final class PlainText {

    private PlainText() {
    }

    public static boolean hasControlCharacters(String value) {
        return value != null && value.codePoints().anyMatch(PlainText::isControl);
    }

    /** Refuses the request when the text isn't plain. Null is allowed; callers check for required text. */
    public static void require(String value, String fieldName) {
        if (hasControlCharacters(value)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    fieldName + " can't contain line breaks or other control characters."
            );
        }
    }

    private static boolean isControl(int codePoint) {
        int type = Character.getType(codePoint);

        return Character.isISOControl(codePoint)
                || type == Character.LINE_SEPARATOR
                || type == Character.PARAGRAPH_SEPARATOR;
    }
}
