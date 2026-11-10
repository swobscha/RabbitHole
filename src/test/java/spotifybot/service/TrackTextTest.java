package spotifybot.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class TrackTextTest {

    @Test
    void versionSuffixesAreRemovedWithoutLosingTheSongTitle() {
        assertEquals("bohemian rhapsody", TrackText.identityTitle("Queen", "Bohemian Rhapsody - Remastered 2011"));
        assertEquals("killer queen", TrackText.identityTitle("Queen", "Killer Queen - 2011 Remaster"));
        assertEquals("halo", TrackText.identityTitle("Beyoncé", "Halo - Live"));
    }

    @Test
    void differentSongsWithTheSameSuffixStayDifferent() {
        assertNotEquals(
                TrackText.identityTitle("Queen", "Bohemian Rhapsody - Remastered 2011"),
                TrackText.identityTitle("Queen", "Killer Queen - Remastered 2011")
        );
    }

    @Test
    void leadingArtistIsRemovedOnlyWhenItIsTheTracksArtist() {
        assertEquals("song", TrackText.identityTitle("Che", "Che - Song"));
        assertEquals("pt 1 intro", TrackText.identityTitle("Che", "Pt. 1 - Intro"));
    }

    @Test
    void wordsThatOnlyContainAKeywordAreKept() {
        assertEquals("song alive", TrackText.identityTitle("A", "Song (Alive)"));
        assertEquals("song left behind", TrackText.searchTitle("A", "Song (Left Behind)"));
    }

    @Test
    void searchTitlesDropCreditsAndAlternateMixes() {
        assertEquals("song", TrackText.searchTitle("A", "Song (feat. B)"));
        assertEquals("song", TrackText.searchTitle("A", "Song - Dave Remix"));
        assertEquals("song", TrackText.searchTitle(null, "Song - Remastered 2011"));
    }

    @Test
    void nonLatinTextIsPreserved() {
        assertEquals("кино", TrackText.simplify("Кино"));
        assertEquals("米津玄師", TrackText.simplify("米津玄師"));
        assertEquals("ガガ", TrackText.simplify("ガガ"));
        assertEquals("мой друг", TrackText.simplify("Мой друг"));
    }

    @Test
    void latinAccentsAreRemoved() {
        assertEquals("beyonce", TrackText.simplify("Beyoncé"));
        assertEquals("sigur ros", TrackText.simplify("Sigur Rós"));
    }

    @Test
    void titleMadeOnlyOfDecorationFallsBackToItsText() {
        assertEquals("live", TrackText.identityTitle("A", "(Live)"));
    }
}
