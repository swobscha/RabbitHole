package rabbithole.model;

/** Asks the server to start loading a seed artist's data before the playlist is requested. */
public record PrefetchRequest(String seedArtist) {
}
