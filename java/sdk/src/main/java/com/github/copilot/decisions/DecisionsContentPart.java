/*---------------------------------------------------------------------------------------------
 *  Copyright (c) Microsoft Corporation. All rights reserved.
 *--------------------------------------------------------------------------------------------*/

package com.github.copilot.decisions;

import java.util.Objects;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.copilot.CopilotExperimental;

/**
 * A content part. The nested marker interfaces restrict which parts are valid
 * in each position, mirroring the wire schema.
 *
 * @since 1.0.0
 */
@CopilotExperimental
public sealed interface DecisionsContentPart {

    /**
     * Parts allowed in user message content: text, image, and JSON.
     */
    sealed interface UserPart extends DecisionsContentPart {
    }

    /**
     * Parts allowed in developer and system message content: text only.
     */
    sealed interface DeveloperPart extends DecisionsContentPart {
    }

    /**
     * Parts allowed in assistant message content: output text and refusals.
     */
    sealed interface AssistantPart extends DecisionsContentPart {
    }

    /**
     * Parts allowed in function and custom tool call output: text and image.
     */
    sealed interface ToolOutputPart extends DecisionsContentPart {
    }

    /**
     * Parts allowed in question instructions: text, image, and JSON. Cache
     * breakpoints are not meaningful in instructions.
     */
    sealed interface QuestionPart extends DecisionsContentPart {
    }

    /**
     * Text content part ({@code input_text}).
     *
     * @param text
     *            the text
     * @param promptCacheBreakpoint
     *            optional cache breakpoint, or {@code null}
     */
    record InputText(String text, DecisionsCacheBreakpoint promptCacheBreakpoint)
            implements
                UserPart,
                DeveloperPart,
                ToolOutputPart,
                QuestionPart {

        /**
         * Validates the components.
         *
         * @param text
         *            the text
         * @param promptCacheBreakpoint
         *            optional cache breakpoint
         */
        public InputText {
            Objects.requireNonNull(text, "text");
        }

        /**
         * Creates a text part without a cache breakpoint.
         *
         * @param text
         *            the text
         */
        public InputText(String text) {
            this(text, null);
        }
    }

    /**
     * Image content part ({@code input_image}).
     *
     * @param imageUrl
     *            a data URL or an {@code http(s)} URL
     * @param detail
     *            optional detail level: {@code "low"}, {@code "high"},
     *            {@code "auto"}, or {@code "original"}; {@code null} to omit
     * @param promptCacheBreakpoint
     *            optional cache breakpoint, or {@code null}
     */
    record InputImage(String imageUrl, String detail,
            DecisionsCacheBreakpoint promptCacheBreakpoint) implements UserPart, ToolOutputPart, QuestionPart {

        /**
         * Validates the components.
         *
         * @param imageUrl
         *            the image URL
         * @param detail
         *            optional detail level
         * @param promptCacheBreakpoint
         *            optional cache breakpoint
         */
        public InputImage {
            Objects.requireNonNull(imageUrl, "imageUrl");
        }

        /**
         * Creates an image part without a detail level or cache breakpoint.
         *
         * @param imageUrl
         *            the image URL
         */
        public InputImage(String imageUrl) {
            this(imageUrl, null, null);
        }

        /**
         * Creates an image part without a cache breakpoint.
         *
         * @param imageUrl
         *            the image URL
         * @param detail
         *            optional detail level
         */
        public InputImage(String imageUrl, String detail) {
            this(imageUrl, detail, null);
        }
    }

    /**
     * Structured JSON content part ({@code input_json}).
     *
     * @param data
     *            a JSON object or array; copied on construction and access
     */
    record InputJson(JsonNode data) implements UserPart, QuestionPart {

        /**
         * Validates and copies the data.
         *
         * @param data
         *            a JSON object or array
         */
        public InputJson {
            Objects.requireNonNull(data, "data");
            if (!data.isObject() && !data.isArray()) {
                throw new IllegalArgumentException("InputJson data must be a JSON object or array.");
            }
            data = data.deepCopy();
        }

        /**
         * Returns a copy of the JSON data.
         *
         * @return the data
         */
        @Override
        public JsonNode data() {
            return data.deepCopy();
        }
    }

    /**
     * Assistant text content part ({@code output_text}).
     *
     * @param text
     *            the text
     */
    record OutputText(String text) implements AssistantPart {

        /**
         * Validates the components.
         *
         * @param text
         *            the text
         */
        public OutputText {
            Objects.requireNonNull(text, "text");
        }
    }

    /**
     * Assistant refusal content part ({@code refusal}).
     *
     * @param refusal
     *            the refusal text
     */
    record Refusal(String refusal) implements AssistantPart {

        /**
         * Validates the components.
         *
         * @param refusal
         *            the refusal text
         */
        public Refusal {
            Objects.requireNonNull(refusal, "refusal");
        }
    }
}
