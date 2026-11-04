/*---------------------------------------------------------------------------------------------
 *  Copyright (c) Microsoft Corporation. All rights reserved.
 *--------------------------------------------------------------------------------------------*/

package com.github.copilot.decisions;

import java.util.Arrays;
import java.util.List;

import com.github.copilot.CopilotExperimental;

/**
 * Message content, tool output, or question instructions: either a plain string
 * or a list of content parts. Exactly one of {@link #text()} and
 * {@link #parts()} is non-null.
 *
 * @param <P>
 *            the content part types allowed in this position
 * @param text
 *            the plain-string form, or {@code null} when {@code parts} is set
 * @param parts
 *            the content parts, or {@code null} when {@code text} is set
 * @since 1.0.0
 */
@CopilotExperimental
public record DecisionsContent<P extends DecisionsContentPart>(String text, List<P> parts) {

    /**
     * Validates the components and copies {@code parts}.
     *
     * @param text
     *            the plain-string form
     * @param parts
     *            the content parts
     */
    public DecisionsContent {
        if ((text == null) == (parts == null)) {
            throw new IllegalArgumentException("Exactly one of text and parts must be set.");
        }
        parts = parts == null ? null : List.copyOf(parts);
    }

    /**
     * Creates plain-string content.
     *
     * @param <P>
     *            the allowed part types
     * @param text
     *            the text
     * @return the content
     */
    public static <P extends DecisionsContentPart> DecisionsContent<P> of(String text) {
        return new DecisionsContent<>(text, null);
    }

    /**
     * Creates content from a list of parts.
     *
     * @param <P>
     *            the allowed part types
     * @param parts
     *            the parts
     * @return the content
     */
    public static <P extends DecisionsContentPart> DecisionsContent<P> of(List<? extends P> parts) {
        return new DecisionsContent<>(null, List.copyOf(parts));
    }

    /**
     * Creates content from parts.
     *
     * @param <P>
     *            the allowed part types
     * @param parts
     *            the parts
     * @return the content
     */
    @SafeVarargs
    public static <P extends DecisionsContentPart> DecisionsContent<P> of(P... parts) {
        return new DecisionsContent<>(null, Arrays.asList(parts));
    }
}
