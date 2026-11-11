package edu.cpsc488.brainfeed.feed;

import edu.cpsc488.brainfeed.ApiException;
import edu.cpsc488.brainfeed.db.TestDatabase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Runs against the real test database (see TestDatabase).
class PlaylistRepositoryTest {

    PlaylistRepository playlists;
    long user;
    long other;
    int math;

    @BeforeEach
    void setUp() {
        playlists = new PlaylistRepository(TestDatabase.get());
        TestDatabase.reset();
        user = TestDatabase.addUser("testuser");
        other = TestDatabase.addUser("otheruser");
        math = TestDatabase.topicId("Math");
        TestDatabase.addVideo("video000001", math);
        TestDatabase.addVideo("video000002", math);
    }

    @Test
    void everyoneGetsAWatchLaterPlaylistFirst() {
        playlists.create(user, "Zoology");
        List<Playlist> mine = playlists.list(user);
        assertTrue(mine.get(0).watchLater());
        assertEquals(PlaylistRepository.WATCH_LATER, mine.get(0).name());
        assertEquals("Zoology", mine.get(1).name());
        // listing twice doesn't make a second Watch later
        assertEquals(2, playlists.list(user).size());
    }

    @Test
    void namesAreUniquePerUserIgnoringCase() {
        playlists.create(user, "Math");
        assertEquals(409, assertThrows(ApiException.class, () -> playlists.create(user, "MATH")).status());
        // someone else can use the same name
        playlists.create(other, "Math");
    }

    @Test
    void anotherUsersPlaylistLooksLikeItDoesntExist() {
        Playlist theirs = playlists.create(other, "Private");
        ApiException add = assertThrows(ApiException.class, () -> playlists.addVideo(user, theirs.id(), "video000001"));
        assertEquals(404, add.status());
        assertFalse(playlists.removeVideo(user, theirs.id(), "video000001"));
        assertFalse(playlists.delete(user, theirs.id()));
        assertEquals(2, playlists.list(other).size());
        assertTrue(playlists.list(user).stream().noneMatch(p -> p.id() == theirs.id()));
    }

    @Test
    void addingAndRemovingVideos() {
        Playlist list = playlists.create(user, "Study");
        playlists.addVideo(user, list.id(), "video000001");
        // adding it again is fine
        playlists.addVideo(user, list.id(), "video000001");
        playlists.addVideo(user, list.id(), "video000002");
        Playlist study = playlists.list(user).stream().filter(p -> p.id() == list.id()).findFirst().orElseThrow();
        assertEquals(2, study.videos().size());
        assertTrue(playlists.removeVideo(user, list.id(), "video000001"));
        assertEquals(1, TestDatabase.number("SELECT count(*) FROM playlist_videos"));
    }

    @Test
    void onlyVideosWeHaveCanBeAdded() {
        Playlist list = playlists.create(user, "Study");
        assertEquals(400, assertThrows(ApiException.class, () -> playlists.addVideo(user, list.id(), "unknown0001")).status());
    }

    @Test
    void watchLaterCantBeDeleted() {
        Playlist watchLater = playlists.list(user).get(0);
        assertFalse(playlists.delete(user, watchLater.id()));
        Playlist mine = playlists.create(user, "Study");
        assertTrue(playlists.delete(user, mine.id()));
    }

    @Test
    void atMostFiftyPlaylistsEvenWithParallelRequests() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<?>> results = new ArrayList<>();
            for (int i = 0; i < 60; i++) {
                String name = "List " + i;
                results.add(pool.submit(() -> {
                    try {
                        playlists.create(user, name);
                    } catch (ApiException e) {
                        // the ones past the limit are refused
                    }
                }));
            }
            for (Future<?> result : results) {
                result.get();
            }
        } finally {
            pool.shutdown();
        }
        assertEquals(PlaylistRepository.MAX_PLAYLISTS,
                TestDatabase.number("SELECT count(*) FROM playlists WHERE user_id = ? AND NOT watch_later", user));
    }

    @Test
    void aBannedVideoLeavesEveryPlaylist() {
        Playlist list = playlists.create(user, "Study");
        playlists.addVideo(user, list.id(), "video000001");
        TestDatabase.exec("DELETE FROM videos WHERE youtube_id = 'video000001'");
        assertEquals(0, TestDatabase.number("SELECT count(*) FROM playlist_videos"));
    }
}
