/*---------------------------------------------------------------------------------------------
 *  Copyright (c) Microsoft Corporation. All rights reserved.
 *--------------------------------------------------------------------------------------------*/

package com.github.copilot.decisions;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.http.HttpHeaders;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.Locale;
import java.util.regex.Pattern;

/** Parses advisory HTTP retry guidance without scheduling retries. */
final class DecisionsRetryAfter {

    private static final long MAX_DELAY = 9007199254740991L;
    private static final Pattern DECIMAL = Pattern.compile("[0-9]+(?:\\.[0-9]+)?");
    private static final Pattern IMF_FIXDATE = Pattern
            .compile("[A-Z][a-z]{2}, [0-9]{2} [A-Z][a-z]{2} [0-9]{4} [0-9]{2}:[0-9]{2}:[0-9]{2} GMT");
    private static final DateTimeFormatter HTTP_DATE = DateTimeFormatter
            .ofPattern("EEE, dd MMM uuuu HH:mm:ss 'GMT'", Locale.US).withResolverStyle(ResolverStyle.STRICT);

    private DecisionsRetryAfter() {
    }

    static Long parse(HttpHeaders headers, long nowEpochMs) {
        Long milliseconds = decimal(headers.firstValue("retry-after-ms").orElse(null), 1);
        if (milliseconds != null) {
            return milliseconds;
        }
        String retryAfter = headers.firstValue("retry-after").orElse(null);
        Long seconds = decimal(retryAfter, 1000);
        if (seconds != null || retryAfter == null) {
            return seconds;
        }
        String value = DecisionsRedaction.trimOws(retryAfter);
        if (!IMF_FIXDATE.matcher(value).matches()) {
            return null;
        }
        try {
            long target = LocalDateTime.parse(value, HTTP_DATE).toInstant(ZoneOffset.UTC).toEpochMilli();
            if (target <= nowEpochMs) {
                return 0L;
            }
            long delay = Math.subtractExact(target, nowEpochMs);
            return delay <= MAX_DELAY ? delay : null;
        } catch (DateTimeParseException | ArithmeticException e) {
            return null;
        }
    }

    private static Long decimal(String raw, int multiplier) {
        if (raw == null) {
            return null;
        }
        String value = DecisionsRedaction.trimOws(raw);
        if (!DECIMAL.matcher(value).matches()) {
            return null;
        }
        try {
            BigDecimal delay = new BigDecimal(value).multiply(BigDecimal.valueOf(multiplier)).setScale(0,
                    RoundingMode.CEILING);
            return delay.compareTo(BigDecimal.valueOf(MAX_DELAY)) <= 0 ? delay.longValueExact() : null;
        } catch (NumberFormatException | ArithmeticException e) {
            return null;
        }
    }
}
