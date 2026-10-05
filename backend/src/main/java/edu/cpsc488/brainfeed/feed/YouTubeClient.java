package edu.cpsc488.brainfeed.feed;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Searches YouTube using the YouTube Data API (search.list). Only the backend talks to YouTube,
 * so the API key never ends up in the browser.
 *
 * <p>We treat everything YouTube sends back like user input: video ids have to look like real
 * YouTube ids, and titles get control characters removed and a length cap.
 *
 * <p>Each search costs 100 of our 10,000 free quota units per day.
 */
public class YouTubeClient {

    private static final String SEARCH_URL = "https://www.googleapis.com/youtube/v3/search";
    private static final Pattern VIDEO_ID = Pattern.compile("^[A-Za-z0-9_-]{11}$");
    static final int MAX_TITLE = 300;
    static final int MAX_CHANNEL = 200;

    private final String apiKey;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    public YouTubeClient(String apiKey) {
        this.apiKey = apiKey;
    }

    /**
     * Gets up to 50 short, embeddable videos for the search query.
     *
     * @throws IOException if YouTube can't be reached or sends back an error. The message never
     *                     has the API key in it, so it's fine to log.
     */
    public List<Video> search(int topicId, String query) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(searchUrl(query, apiKey)))
                .timeout(Duration.ofSeconds(10))
                .header("Accept", "application/json")
                .GET()
                .build();

        HttpResponse<String> response;
        try {
            response = client.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            // Only log the exception type, just in case a message ever includes the URL (and key).
            throw new IOException("YouTube request failed: " + e.getClass().getSimpleName());
        }

        JsonNode body = mapper.readTree(response.body());
        if (response.statusCode() != 200) {
            throw new IOException("YouTube returned HTTP " + response.statusCode() + " (" + errorReason(body) + ")");
        }
        return parseResults(body, topicId);
    }

    static String searchUrl(String query, String apiKey) {
        return SEARCH_URL
                + "?part=snippet&type=video&maxResults=50"
                + "&videoEmbeddable=true&safeSearch=strict"
                + "&videoDuration=short"   // under 4 minutes
                + "&videoCategoryId=27"    // YouTube's "Education" category
                + "&relevanceLanguage=en"
                + "&q=" + URLEncoder.encode(query, StandardCharsets.UTF_8)
                + "&key=" + URLEncoder.encode(apiKey, StandardCharsets.UTF_8);
    }

    /** Turns a search.list response into videos, skipping anything that looks wrong. */
    static List<Video> parseResults(JsonNode body, int topicId) {
        List<Video> videos = new ArrayList<>();
        for (JsonNode item : body.path("items")) {
            String id = item.path("id").path("videoId").asText("");
            JsonNode snippet = item.path("snippet");
            if (!VIDEO_ID.matcher(id).matches()) {
                continue;
            }
            // skip live streams and upcoming premieres
            if (!snippet.path("liveBroadcastContent").asText("none").equals("none")) {
                continue;
            }
            try {
                videos.add(new Video(
                        id,
                        clean(unescapeHtml(snippet.path("title").asText("")), MAX_TITLE),
                        clean(unescapeHtml(snippet.path("channelTitle").asText("")), MAX_CHANNEL),
                        topicId,
                        OffsetDateTime.parse(snippet.path("publishedAt").asText())));
            } catch (DateTimeParseException e) {
                // bad date, skip this one
            }
        }
        return videos;
    }

    /** The short reason YouTube gives for an error, like "quotaExceeded" or "keyInvalid". */
    static String errorReason(JsonNode body) {
        String reason = body.path("error").path("errors").path(0).path("reason").asText("unknown");
        return clean(reason, 100);
    }

    /**
     * YouTube sends titles HTML-escaped (Don&#39;t). React already escapes text when it shows it,
     * so we change them back to normal characters or users would literally see "&#39;".
     * &amp; has to go last so "&amp;lt;" ends up as "&lt;" and not "<".
     */
    static String unescapeHtml(String s) {
        return s.replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&amp;", "&");
    }

    static String clean(String s, int maxLength) {
        String cleaned = s.replaceAll("\\p{Cntrl}", " ").strip();
        return cleaned.length() > maxLength ? cleaned.substring(0, maxLength) : cleaned;
    }
}
