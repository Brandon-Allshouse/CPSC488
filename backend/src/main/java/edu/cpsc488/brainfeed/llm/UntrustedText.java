package edu.cpsc488.brainfeed.llm;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.regex.Pattern;

/**
 * Cleaning for text going into or coming out of the LLM. Both directions are untrusted: what goes
 * in was written by a YouTube uploader or a user (possible prompt injection, OWASP LLM01), and
 * what comes out was written by the model (OWASP LLM05).
 */
public final class UntrustedText {

    private static final Pattern URLS = Pattern.compile("https?://\\S+");
    private static final Pattern CONTROL = Pattern.compile("\\p{Cntrl}");
    private static final Pattern SPACES = Pattern.compile("\\s+");

    private UntrustedText() {
    }

    /**
     * For putting someone else's text inside our prompt's tags. Removes angle brackets (so it
     * can't close the tag early), links, control characters and runs of whitespace, so it can't
     * start fake new lines either. Then caps the length.
     */
    public static String forPrompt(String text, int maxLength) {
        if (text == null) {
            return "";
        }
        String cleaned = URLS.matcher(text).replaceAll(" ").replace('<', ' ').replace('>', ' ');
        return cap(oneLine(cleaned), maxLength);
    }

    /**
     * Reads a string field the model wrote: control characters and extra whitespace removed. Null
     * if it isn't a string, is blank after cleaning, or is longer than maxLength (a reply that
     * long is a sign something went wrong, so it's refused rather than cut).
     */
    public static String field(JsonNode node, int maxLength) {
        if (node == null || !node.isTextual()) {
            return null;
        }
        String cleaned = oneLine(node.textValue());
        return cleaned.isEmpty() || cleaned.length() > maxLength ? null : cleaned;
    }

    /** Control characters become spaces, whitespace runs become one space, and the ends are trimmed. */
    public static String oneLine(String text) {
        String cleaned = CONTROL.matcher(text).replaceAll(" ");
        return SPACES.matcher(cleaned).replaceAll(" ").strip();
    }

    /**
     * Drops a {@code <think>...</think>} block from the start of a reply. Some models (Qwen 3,
     * DeepSeek) write their reasoning there before the JSON. We turn thinking off in the request,
     * but strip it in case a model ignores that.
     */
    public static String stripThinking(String text) {
        int end = text.lastIndexOf("</think>");
        return end >= 0 ? text.substring(end + "</think>".length()) : text;
    }

    // Never cuts an emoji in half: a lone surrogate would become a broken character.
    private static String cap(String text, int maxLength) {
        if (text.length() <= maxLength) {
            return text;
        }
        int end = Character.isHighSurrogate(text.charAt(maxLength - 1)) ? maxLength - 1 : maxLength;
        return text.substring(0, end).strip();
    }
}
