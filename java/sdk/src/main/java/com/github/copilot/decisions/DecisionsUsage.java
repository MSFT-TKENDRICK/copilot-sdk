/*---------------------------------------------------------------------------------------------
 *  Copyright (c) Microsoft Corporation. All rights reserved.
 *--------------------------------------------------------------------------------------------*/

package com.github.copilot.decisions;

import java.util.Objects;

import com.github.copilot.CopilotExperimental;

/**
 * Token usage of a Decisions API call. Missing counts are reported as zero.
 *
 * @param inputTokens
 *            input tokens
 * @param inputTokensDetails
 *            input token breakdown
 * @param outputTokens
 *            output tokens
 * @param outputTokensDetails
 *            output token breakdown
 * @param totalTokens
 *            total tokens
 * @since 1.0.0
 */
@CopilotExperimental
public record DecisionsUsage(long inputTokens, InputTokensDetails inputTokensDetails, long outputTokens,
        OutputTokensDetails outputTokensDetails, long totalTokens) {

    /**
     * Validates the components.
     *
     * @param inputTokens
     *            input tokens
     * @param inputTokensDetails
     *            input token breakdown
     * @param outputTokens
     *            output tokens
     * @param outputTokensDetails
     *            output token breakdown
     * @param totalTokens
     *            total tokens
     */
    public DecisionsUsage {
        Objects.requireNonNull(inputTokensDetails, "inputTokensDetails");
        Objects.requireNonNull(outputTokensDetails, "outputTokensDetails");
    }

    /**
     * Input token breakdown.
     *
     * @param cachedTokens
     *            tokens served from the prompt cache
     * @param cacheWriteTokens
     *            tokens written to the prompt cache
     */
    public record InputTokensDetails(long cachedTokens, long cacheWriteTokens) {
    }

    /**
     * Output token breakdown.
     *
     * @param reasoningTokens
     *            reasoning tokens
     */
    public record OutputTokensDetails(long reasoningTokens) {
    }
}
