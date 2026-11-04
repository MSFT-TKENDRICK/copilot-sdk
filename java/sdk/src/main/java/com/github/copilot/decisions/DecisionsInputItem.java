/*---------------------------------------------------------------------------------------------
 *  Copyright (c) Microsoft Corporation. All rights reserved.
 *--------------------------------------------------------------------------------------------*/

package com.github.copilot.decisions;

import java.util.Objects;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.copilot.CopilotExperimental;
import com.github.copilot.decisions.DecisionsContentPart.AssistantPart;
import com.github.copilot.decisions.DecisionsContentPart.DeveloperPart;
import com.github.copilot.decisions.DecisionsContentPart.ToolOutputPart;
import com.github.copilot.decisions.DecisionsContentPart.UserPart;

/**
 * A conversation item in {@link DecisionsRequest#getInputItems()}.
 * <p>
 * Optional {@code String} components set to {@code null} are omitted from the
 * wire request. Item {@code status} values are {@code "in_progress"},
 * {@code "completed"}, or {@code "incomplete"}.
 *
 * @since 1.0.0
 */
@CopilotExperimental
public sealed interface DecisionsInputItem {

    /**
     * A user message.
     *
     * @param content
     *            string content or user parts (text, image, JSON)
     * @param explicitType
     *            whether to send {@code "type":"message"} explicitly
     */
    record UserMessage(DecisionsContent<UserPart> content, boolean explicitType) implements DecisionsInputItem {

        /**
         * Validates the components.
         *
         * @param content
         *            the content
         * @param explicitType
         *            whether to send the item type
         */
        public UserMessage {
            Objects.requireNonNull(content, "content");
        }

        /**
         * Creates an untyped user message.
         *
         * @param content
         *            the content
         */
        public UserMessage(DecisionsContent<UserPart> content) {
            this(content, false);
        }

        /**
         * Creates an untyped user message with string content.
         *
         * @param text
         *            the text
         */
        public UserMessage(String text) {
            this(DecisionsContent.of(text), false);
        }
    }

    /**
     * A developer or system message.
     *
     * @param role
     *            {@code "developer"} or {@code "system"}
     * @param content
     *            string content or text parts
     * @param explicitType
     *            whether to send {@code "type":"message"} explicitly
     */
    record DeveloperMessage(String role, DecisionsContent<DeveloperPart> content,
            boolean explicitType) implements DecisionsInputItem {

        /**
         * Validates the components.
         *
         * @param role
         *            the role
         * @param content
         *            the content
         * @param explicitType
         *            whether to send the item type
         */
        public DeveloperMessage {
            if (!"developer".equals(role) && !"system".equals(role)) {
                throw new IllegalArgumentException("DeveloperMessage role must be 'developer' or 'system'.");
            }
            Objects.requireNonNull(content, "content");
        }

        /**
         * Creates an untyped message.
         *
         * @param role
         *            {@code "developer"} or {@code "system"}
         * @param content
         *            the content
         */
        public DeveloperMessage(String role, DecisionsContent<DeveloperPart> content) {
            this(role, content, false);
        }

        /**
         * Creates an untyped message with string content.
         *
         * @param role
         *            {@code "developer"} or {@code "system"}
         * @param text
         *            the text
         */
        public DeveloperMessage(String role, String text) {
            this(role, DecisionsContent.of(text), false);
        }
    }

    /**
     * An assistant message.
     *
     * @param content
     *            string content or assistant parts (output text, refusal)
     * @param phase
     *            optional phase: {@code "commentary"} or {@code "final_answer"}
     * @param explicitType
     *            whether to send {@code "type":"message"} explicitly
     */
    record AssistantMessage(DecisionsContent<AssistantPart> content, String phase,
            boolean explicitType) implements DecisionsInputItem {

        /**
         * Validates the components.
         *
         * @param content
         *            the content
         * @param phase
         *            optional phase
         * @param explicitType
         *            whether to send the item type
         */
        public AssistantMessage {
            Objects.requireNonNull(content, "content");
        }

        /**
         * Creates an untyped assistant message without a phase.
         *
         * @param content
         *            the content
         */
        public AssistantMessage(DecisionsContent<AssistantPart> content) {
            this(content, null, false);
        }

        /**
         * Creates an untyped assistant message with string content.
         *
         * @param text
         *            the text
         */
        public AssistantMessage(String text) {
            this(DecisionsContent.of(text), null, false);
        }
    }

    /**
     * A function call ({@code function_call}).
     *
     * @param callId
     *            the call ID
     * @param name
     *            the function name
     * @param arguments
     *            the JSON-encoded arguments
     * @param id
     *            optional item ID
     * @param namespace
     *            optional namespace
     * @param status
     *            optional item status
     */
    record FunctionCall(String callId, String name, String arguments, String id, String namespace,
            String status) implements DecisionsInputItem {

        /**
         * Validates the components.
         *
         * @param callId
         *            the call ID
         * @param name
         *            the function name
         * @param arguments
         *            the arguments
         * @param id
         *            optional item ID
         * @param namespace
         *            optional namespace
         * @param status
         *            optional status
         */
        public FunctionCall {
            Objects.requireNonNull(callId, "callId");
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(arguments, "arguments");
        }

        /**
         * Creates a function call with only the required fields.
         *
         * @param callId
         *            the call ID
         * @param name
         *            the function name
         * @param arguments
         *            the JSON-encoded arguments
         */
        public FunctionCall(String callId, String name, String arguments) {
            this(callId, name, arguments, null, null, null);
        }
    }

    /**
     * A function call result ({@code function_call_output}).
     *
     * @param callId
     *            the call ID
     * @param output
     *            string output or text/image parts
     * @param id
     *            optional item ID
     * @param status
     *            optional item status
     */
    record FunctionCallOutput(String callId, DecisionsContent<ToolOutputPart> output, String id,
            String status) implements DecisionsInputItem {

        /**
         * Validates the components.
         *
         * @param callId
         *            the call ID
         * @param output
         *            the output
         * @param id
         *            optional item ID
         * @param status
         *            optional status
         */
        public FunctionCallOutput {
            Objects.requireNonNull(callId, "callId");
            Objects.requireNonNull(output, "output");
        }

        /**
         * Creates a function call output with only the required fields.
         *
         * @param callId
         *            the call ID
         * @param output
         *            the output
         */
        public FunctionCallOutput(String callId, DecisionsContent<ToolOutputPart> output) {
            this(callId, output, null, null);
        }

        /**
         * Creates a function call output with string output.
         *
         * @param callId
         *            the call ID
         * @param output
         *            the output text
         */
        public FunctionCallOutput(String callId, String output) {
            this(callId, DecisionsContent.of(output), null, null);
        }
    }

    /**
     * A custom tool call ({@code custom_tool_call}).
     *
     * @param callId
     *            the call ID
     * @param name
     *            the tool name
     * @param input
     *            the free-form tool input
     * @param id
     *            optional item ID
     * @param namespace
     *            optional namespace
     * @param status
     *            optional item status
     */
    record CustomToolCall(String callId, String name, String input, String id, String namespace,
            String status) implements DecisionsInputItem {

        /**
         * Validates the components.
         *
         * @param callId
         *            the call ID
         * @param name
         *            the tool name
         * @param input
         *            the tool input
         * @param id
         *            optional item ID
         * @param namespace
         *            optional namespace
         * @param status
         *            optional status
         */
        public CustomToolCall {
            Objects.requireNonNull(callId, "callId");
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(input, "input");
        }

        /**
         * Creates a custom tool call with only the required fields.
         *
         * @param callId
         *            the call ID
         * @param name
         *            the tool name
         * @param input
         *            the tool input
         */
        public CustomToolCall(String callId, String name, String input) {
            this(callId, name, input, null, null, null);
        }
    }

    /**
     * A custom tool call result ({@code custom_tool_call_output}).
     *
     * @param callId
     *            the call ID
     * @param output
     *            string output or text/image parts
     * @param id
     *            optional item ID
     * @param status
     *            optional item status
     */
    record CustomToolCallOutput(String callId, DecisionsContent<ToolOutputPart> output, String id,
            String status) implements DecisionsInputItem {

        /**
         * Validates the components.
         *
         * @param callId
         *            the call ID
         * @param output
         *            the output
         * @param id
         *            optional item ID
         * @param status
         *            optional status
         */
        public CustomToolCallOutput {
            Objects.requireNonNull(callId, "callId");
            Objects.requireNonNull(output, "output");
        }

        /**
         * Creates a custom tool call output with string output.
         *
         * @param callId
         *            the call ID
         * @param output
         *            the output text
         */
        public CustomToolCallOutput(String callId, String output) {
            this(callId, DecisionsContent.of(output), null, null);
        }
    }

    /**
     * An input item sent verbatim (already in wire format). Use for item types this
     * SDK version does not model yet.
     *
     * @param raw
     *            the wire JSON object; copied on construction and access
     */
    record Raw(ObjectNode raw) implements DecisionsInputItem {

        /**
         * Validates and copies the components.
         *
         * @param raw
         *            the wire JSON object
         */
        public Raw {
            Objects.requireNonNull(raw, "raw");
            raw = raw.deepCopy();
        }

        /**
         * Returns a copy of the wire JSON object.
         *
         * @return the raw item
         */
        @Override
        public ObjectNode raw() {
            return raw.deepCopy();
        }
    }
}
