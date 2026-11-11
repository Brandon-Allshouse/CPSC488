package edu.cpsc488.brainfeed.feed;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Tests reading YouTube's responses. Nothing here calls the real YouTube API.
class YouTubeClientTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // builds one search result the way YouTube sends it
    private static String item(String videoId, String title, String live, String publishedAt) {
        return """
                {"id": {"videoId": "%s"},
                 "snippet": {"title": "%s", "channelTitle": "Test Channel",
                             "liveBroadcastContent": "%s", "publishedAt": "%s"}}
                """.formatted(videoId, title, live, publishedAt);
    }

    private static List<Video> parse(String... items) throws Exception {
        JsonNode body = MAPPER.readTree("{\"items\": [" + String.join(",", items) + "]}");
        return YouTubeClient.parseResults(body, 3);
    }

    @Test
    void readsANormalVideo() throws Exception {
        List<Video> videos = parse(item("dQw4w9WgXcQ", "How Plants Grow", "none", "2026-01-15T10:00:00Z"));
        assertEquals(1, videos.size());
        Video video = videos.get(0);
        assertEquals("dQw4w9WgXcQ", video.youtubeId());
        assertEquals("How Plants Grow", video.title());
        assertEquals("Test Channel", video.channelTitle());
        assertEquals(3, video.topicId());
        assertEquals(2026, video.publishedAt().getYear());
    }

    @Test
    void skipsVideoIdsThatDontLookRight() throws Exception {
        // ids get put into the embed URL, so anything weird is thrown out
        List<Video> videos = parse(
                item("short", "Too short", "none", "2026-01-15T10:00:00Z"),
                item("abc/../defgh", "Has slashes", "none", "2026-01-15T10:00:00Z"),
                item("dQw4w9WgXcQ", "Fine", "none", "2026-01-15T10:00:00Z"));
        assertEquals(1, videos.size());
        assertEquals("Fine", videos.get(0).title());
    }

    @Test
    void skipsLiveStreamsAndPremieres() throws Exception {
        List<Video> videos = parse(
                item("aaaaaaaaaaa", "Live now", "live", "2026-01-15T10:00:00Z"),
                item("bbbbbbbbbbb", "Coming soon", "upcoming", "2026-01-15T10:00:00Z"));
        assertTrue(videos.isEmpty());
    }

    @Test
    void skipsVideosWithABadDate() throws Exception {
        assertTrue(parse(item("dQw4w9WgXcQ", "Bad date", "none", "yesterday")).isEmpty());
    }

    @Test
    void noItemsMeansNoVideos() throws Exception {
        assertTrue(YouTubeClient.parseResults(MAPPER.readTree("{}"), 1).isEmpty());
    }

    @Test
    void htmlCodesInTitlesAreTurnedBackIntoCharacters() {
        assertEquals("Don't \"panic\" <3 & relax", YouTubeClient.unescapeHtml("Don&#39;t &quot;panic&quot; &lt;3 &amp; relax"));
    }

    @Test
    void escapedAmpersandIsOnlyUnescapedOnce() {
        // "&amp;lt;" was a literal "&lt;" in the original title, not a "<"
        assertEquals("&lt;", YouTubeClient.unescapeHtml("&amp;lt;"));
    }

    @Test
    void cleanRemovesControlCharactersAndCutsLength() {
        assertEquals("line one  line two", YouTubeClient.clean("line one\n\tline two", 100));
        assertEquals("abc", YouTubeClient.clean("abcdef", 3));
    }

    @Test
    void longTitlesAreCapped() throws Exception {
        String longTitle = "a".repeat(YouTubeClient.MAX_TITLE + 50);
        Video video = parse(item("dQw4w9WgXcQ", longTitle, "none", "2026-01-15T10:00:00Z")).get(0);
        assertEquals(YouTubeClient.MAX_TITLE, video.title().length());
    }

    @Test
    void readsTheErrorReason() throws Exception {
        JsonNode body = MAPPER.readTree("{\"error\": {\"code\": 403, \"errors\": [{\"reason\": \"quotaExceeded\"}]}}");
        assertEquals("quotaExceeded", YouTubeClient.errorReason(body));
    }

    @Test
    void missingErrorReasonSaysUnknown() throws Exception {
        assertEquals("unknown", YouTubeClient.errorReason(MAPPER.readTree("{}")));
    }

    @Test
    void searchUrlEncodesTheQueryAndKey() {
        String url = YouTubeClient.searchUrl("cats & dogs", "key+with/odd=chars");
        assertTrue(url.contains("&q=cats+%26+dogs"), url);
        assertTrue(url.contains("&key=key%2Bwith%2Fodd%3Dchars"), url);
    }

    @Test
    void searchUrlAsksForSafeShortEducationalVideos() {
        String url = YouTubeClient.searchUrl("math", "test-key");
        assertTrue(url.contains("safeSearch=strict"));
        assertTrue(url.contains("videoEmbeddable=true"));
        assertTrue(url.contains("videoDuration=short"));
        assertTrue(url.contains("videoCategoryId=27"));
        assertFalse(url.contains(" "));
    }

    @Test
    void readsTheDescriptionFromVideosList() throws Exception {
        JsonNode body = MAPPER.readTree("{\"items\": [{\"snippet\": {\"description\": \"Slopes of tangent lines.\\nSubscribe!\"}}]}");
        assertEquals("Slopes of tangent lines.\nSubscribe!", YouTubeClient.parseDescription(body).orElseThrow());
    }

    @Test
    void noDescriptionIsEmpty() throws Exception {
        assertTrue(YouTubeClient.parseDescription(MAPPER.readTree("{\"items\": []}")).isEmpty());
        assertTrue(YouTubeClient.parseDescription(MAPPER.readTree("{\"items\": [{\"snippet\": {\"description\": \"  \"}}]}")).isEmpty());
    }

    @Test
    void veryLongDescriptionsAreCut() throws Exception {
        String longText = "a".repeat(YouTubeClient.MAX_DESCRIPTION + 100);
        JsonNode body = MAPPER.readTree("{\"items\": [{\"snippet\": {\"description\": \"" + longText + "\"}}]}");
        assertEquals(YouTubeClient.MAX_DESCRIPTION, YouTubeClient.parseDescription(body).orElseThrow().length());
    }

    @Test
    void videosUrlOnlyAsksForTheDescription() {
        String url = YouTubeClient.videosUrl("dQw4w9WgXcQ", "test-key");
        assertTrue(url.startsWith("https://www.googleapis.com/youtube/v3/videos?part=snippet&id=dQw4w9WgXcQ"), url);
        assertTrue(url.contains("&fields=items%28snippet%28description%29%29"), url);
        assertFalse(url.contains(" "));
    }
}
