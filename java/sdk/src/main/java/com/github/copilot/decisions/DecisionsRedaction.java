/*---------------------------------------------------------------------------------------------
 *  Copyright (c) Microsoft Corporation. All rights reserved.
 *--------------------------------------------------------------------------------------------*/

package com.github.copilot.decisions;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Credential redaction shared by every SDK language: secret variants, case
 * insensitive escape matching, and longest-first replacement.
 */
final class DecisionsRedaction {

    static final String REDACTED = "[REDACTED]";
    static final int MIN_SECRET_LENGTH = 8;

    private static final String URI_UNRESERVED = "-_.!~*'()";
    private static final char[] HEX_UPPER = "0123456789ABCDEF".toCharArray();

    private DecisionsRedaction() {
    }

    /** Trims leading and trailing SP and HTAB (HTTP optional whitespace). */
    static String trimOws(String value) {
        int start = 0;
        int end = value.length();
        while (start < end && isOws(value.charAt(start))) {
            start++;
        }
        while (end > start && isOws(value.charAt(end - 1))) {
            end--;
        }
        return value.substring(start, end);
    }

    private static boolean isOws(char c) {
        return c == ' ' || c == '\t';
    }

    /**
     * Expands trimmed raw secrets (deduplicated, in order) into their variants,
     * sorted longest first (stable).
     */
    static List<String> expand(Set<String> rawSecrets) {
        Set<String> variants = new LinkedHashSet<>();
        for (String secret : rawSecrets) {
            variants.addAll(secretVariants(secret));
        }
        List<String> sorted = new ArrayList<>(variants);
        sorted.sort(Comparator.comparingInt(String::length).reversed());
        return List.copyOf(sorted);
    }

    /** Adds {@code value} to {@code raw} if it is a secret candidate. */
    static void addCandidate(Set<String> raw, String value) {
        if (value == null) {
            return;
        }
        String trimmed = trimOws(value);
        if (trimmed.codePointCount(0, trimmed.length()) >= MIN_SECRET_LENGTH) {
            raw.add(trimmed);
        }
    }

    static List<String> secretVariants(String secret) {
        String jsonEscaped = jsonEscape(secret);
        String asciiJsonEscaped = asciiEscape(jsonEscaped);
        List<String> variants = new ArrayList<>();
        variants.add(secret);
        variants.add(jsonEscaped);
        variants.add(asciiJsonEscaped);
        variants.add(jsonEscaped.replace("/", "\\/"));
        variants.add(asciiJsonEscaped.replace("/", "\\/"));
        String uriEncoded = encodeUriComponent(secret);
        variants.add(uriEncoded);
        variants.add(uriEncoded.replace("%20", "+"));
        return variants;
    }

    /** Body of {@code JSON.stringify(value)} without the surrounding quotes. */
    static String jsonEscape(String value) {
        StringBuilder out = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        appendUnicodeEscape(out, c);
                    } else if (Character.isHighSurrogate(c)) {
                        if (i + 1 < value.length() && Character.isLowSurrogate(value.charAt(i + 1))) {
                            out.append(c).append(value.charAt(i + 1));
                            i++;
                        } else {
                            appendUnicodeEscape(out, c);
                        }
                    } else if (Character.isLowSurrogate(c)) {
                        appendUnicodeEscape(out, c);
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.toString();
    }

    /** Replaces every UTF-16 code unit at or above U+007F with {@code \\uxxxx}. */
    static String asciiEscape(String value) {
        StringBuilder out = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c >= 0x7F) {
                appendUnicodeEscape(out, c);
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    private static void appendUnicodeEscape(StringBuilder out, char c) {
        String hex = Integer.toHexString(c);
        out.append("\\u");
        for (int pad = hex.length(); pad < 4; pad++) {
            out.append('0');
        }
        out.append(hex);
    }

    /**
     * JavaScript {@code encodeURIComponent}, except that lone surrogates are
     * encoded as U+FFFD instead of throwing.
     */
    static String encodeUriComponent(String value) {
        StringBuilder out = new StringBuilder(value.length() * 3);
        byte[] bytes = replaceLoneSurrogates(value).getBytes(StandardCharsets.UTF_8);
        for (byte b : bytes) {
            int v = b & 0xFF;
            if (v < 0x80 && (Character.isLetterOrDigit(v) || URI_UNRESERVED.indexOf(v) >= 0)) {
                out.append((char) v);
            } else {
                out.append('%').append(HEX_UPPER[v >> 4]).append(HEX_UPPER[v & 0xF]);
            }
        }
        return out.toString();
    }

    private static String replaceLoneSurrogates(String value) {
        StringBuilder out = null;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            boolean lone = false;
            if (Character.isHighSurrogate(c)) {
                if (i + 1 < value.length() && Character.isLowSurrogate(value.charAt(i + 1))) {
                    if (out != null) {
                        out.append(c).append(value.charAt(i + 1));
                    }
                    i++;
                    continue;
                }
                lone = true;
            } else if (Character.isLowSurrogate(c)) {
                lone = true;
            }
            if (lone && out == null) {
                out = new StringBuilder(value.length()).append(value, 0, i);
            }
            if (out != null) {
                out.append(lone ? '\uFFFD' : c);
            }
        }
        return out == null ? value : out.toString();
    }

    /**
     * Regex for one variant. Hex digits in {@code \\uXXXX} and {@code %XX} escapes
     * match case-insensitively.
     */
    static String variantPattern(String variant) {
        StringBuilder out = new StringBuilder(variant.length() * 2);
        int i = 0;
        while (i < variant.length()) {
            if (variant.startsWith("\\u", i) && isHex(variant, i + 2, 4)) {
                out.append("\\\\u");
                appendHexClass(out, variant, i + 2, 4);
                i += 6;
            } else if (variant.charAt(i) == '%' && isHex(variant, i + 1, 2)) {
                out.append('%');
                appendHexClass(out, variant, i + 1, 2);
                i += 3;
            } else {
                char c = variant.charAt(i);
                if (c < 0x80 && !Character.isLetterOrDigit(c)) {
                    out.append('\\');
                }
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    private static boolean isHex(String s, int start, int count) {
        if (start + count > s.length()) {
            return false;
        }
        for (int i = start; i < start + count; i++) {
            if (Character.digit(s.charAt(i), 16) < 0 || s.charAt(i) > 0x7F) {
                return false;
            }
        }
        return true;
    }

    private static void appendHexClass(StringBuilder out, String s, int start, int count) {
        for (int i = start; i < start + count; i++) {
            char c = s.charAt(i);
            if (Character.isLetter(c)) {
                out.append('[').append(Character.toLowerCase(c)).append(Character.toUpperCase(c)).append(']');
            } else {
                out.append(c);
            }
        }
    }

    /** Replaces every occurrence of every variant with {@code [REDACTED]}. */
    static String redact(String text, List<String> secrets) {
        String result = text;
        for (String secret : secrets) {
            result = Pattern.compile(variantPattern(secret)).matcher(result)
                    .replaceAll(Matcher.quoteReplacement(REDACTED));
        }
        return result;
    }

    /** UTF-8 byte length of the longest variant. */
    static int maxSecretBytes(List<String> secrets) {
        int max = 0;
        for (String secret : secrets) {
            max = Math.max(max, secret.getBytes(StandardCharsets.UTF_8).length);
        }
        return max;
    }
}
