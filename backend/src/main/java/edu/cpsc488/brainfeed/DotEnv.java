package edu.cpsc488.brainfeed;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal .env reader, so secrets live in a gitignored file instead of the source code.
 *
 * <p>Only matters when running the backend outside Docker. Inside Docker there's no .env file in
 * the container; docker-compose.yml passes the same values in as environment variables instead.
 *
 * <p>Looks for .env in the working directory, then its parent. That way it finds the repo-root
 * .env whether the backend is started from the repo root or from backend/.
 * Real environment variables always take precedence (see {@code App.Config}).
 *
 * <p>Supported syntax: {@code KEY=value} lines, {@code #} comment lines, and optional surrounding
 * quotes. Not supported: {@code export KEY=...}, multi-line values, escapes, or {@code ${VAR}}
 * references. Keep .env simple, since docker compose reads the same file.
 */
final class DotEnv {

    private DotEnv() {
    }

    static Map<String, String> load() {
        for (Path candidate : List.of(Path.of(".env"), Path.of("..", ".env"))) {
            if (Files.isRegularFile(candidate)) {
                return parse(candidate);
            }
        }
        return Map.of();
    }

    private static Map<String, String> parse(Path file) {
        Map<String, String> values = new HashMap<>();
        try {
            for (String raw : Files.readAllLines(file)) {
                // Some Windows editors put an invisible byte-order mark at the start of the file,
                // which would otherwise become part of the first key's name.
                String line = raw.replace("﻿", "").strip();
                if (line.isEmpty() || line.startsWith("#") || !line.contains("=")) {
                    continue;
                }
                String key = line.substring(0, line.indexOf('=')).strip();
                String value = line.substring(line.indexOf('=') + 1).strip();
                // KEY="value" and KEY='value' both mean value.
                if (value.length() >= 2
                        && (value.startsWith("\"") && value.endsWith("\"") || value.startsWith("'") && value.endsWith("'"))) {
                    value = value.substring(1, value.length() - 1);
                }
                values.put(key, value);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Could not read " + file.toAbsolutePath(), e);
        }
        return values;
    }
}
