package rabbithole.service;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** Shared cleanup for artist names and track titles, for every alphabet. */
public final class TrackText {

    private static final Pattern LATIN_ACCENTS = Pattern.compile("(?<=\\p{IsLatin})\\p{InCombiningDiacriticalMarks}+");
    private static final Pattern NOT_LETTER_OR_NUMBER = Pattern.compile("[^\\p{L}\\p{M}\\p{N}]+");
    private static final Pattern DASH_SEPARATOR = Pattern.compile("\\s+[-–—]\\s+");

    /** Decorations on the same recording, such as " - Remastered 2011" or "(Live)". */
    private static final List<Pattern> SAME_RECORDING_NOISE = List.of(
            Pattern.compile("[(\\[]\\s*(?:explicit|clean)\\s*[)\\]]"),
            Pattern.compile("[(\\[][^)\\]]*\\b(?:music\\s+video|mv|live)\\b[^)\\]]*[)\\]]"),
            Pattern.compile("\\s+[-–—]\\s+(?:\\d{4}\\s+)?(?:digital(?:ly)?\\s+)?"
                    + "(?:(?:re-?master(?:ed)?|live|mono|stereo|radio\\s+edit|single\\s+version|album\\s+version)\\b.*"
                    + "|explicit|clean)$"),
            Pattern.compile("\\s+(?:live\\s+)?complexcon\\s+\\d{4}.*$"),
            Pattern.compile("^\\d+\\.\\s*"),
            Pattern.compile("\\s+(?:mv|music\\s+video)$")
    );

    /** Also drops credits and alternate mixes, which vary too much between catalogs to compare. */
    private static final List<Pattern> SEARCH_NOISE = Stream.concat(
            SAME_RECORDING_NOISE.stream(),
            Stream.of(
                    Pattern.compile("[(\\[][^)\\]]*\\b(?:feat|featuring|ft|with|prod|produced"
                            + "|re-?master(?:ed)?|remix(?:ed)?|version)\\b[^)\\]]*[)\\]]"),
                    Pattern.compile("\\s+[-–—]\\s+(?:.*\\s)?(?:remix(?:ed)?|version|edit|mix)\\b.*$")
            )
    ).toList();

    private TrackText() {
    }

    /** Lower-cases, removes accents from Latin letters, and turns punctuation into single spaces. */
    public static String simplify(String value) {
        if (value == null) {
            return "";
        }

        String decomposed = Normalizer.normalize(value, Normalizer.Form.NFD);
        String withoutAccents = LATIN_ACCENTS.matcher(decomposed).replaceAll("");
        String recomposed = Normalizer.normalize(withoutAccents, Normalizer.Form.NFC);

        return NOT_LETTER_OR_NUMBER.matcher(recomposed.toLowerCase(Locale.ROOT)).replaceAll(" ").trim();
    }

    /** Title used to decide whether two tracks are the same recording. */
    public static String identityTitle(String artistName, String title) {
        return cleanTitle(artistName, title, SAME_RECORDING_NOISE);
    }

    /** Title used to search Spotify and compare its results. */
    public static String searchTitle(String artistName, String title) {
        return cleanTitle(artistName, title, SEARCH_NOISE);
    }

    private static String cleanTitle(String artistName, String title, List<Pattern> noise) {
        if (title == null) {
            return "";
        }

        String text = withoutArtistPrefix(artistName, title).toLowerCase(Locale.ROOT);

        for (Pattern pattern : noise) {
            text = pattern.matcher(text).replaceAll("");
        }

        String cleaned = simplify(text);

        return cleaned.isBlank() ? simplify(title) : cleaned;
    }

    /** Removes a leading "Artist - " only when it really is this track's artist. */
    private static String withoutArtistPrefix(String artistName, String title) {
        String artist = simplify(artistName);
        Matcher separator = DASH_SEPARATOR.matcher(title);

        if (!artist.isBlank() && separator.find() && artist.equals(simplify(title.substring(0, separator.start())))) {
            return title.substring(separator.end());
        }

        return title;
    }
}
