/*---------------------------------------------------------------------------------------------
 *  Copyright (c) Microsoft Corporation. All rights reserved.
 *--------------------------------------------------------------------------------------------*/

package com.github.copilot.decisions;

import java.util.List;
import java.util.Objects;

import com.github.copilot.CopilotExperimental;

/**
 * The result of a Decisions API call.
 *
 * @param answers
 *            one answer per question, in question order
 * @param model
 *            the model that produced the answers
 * @param usage
 *            token usage
 * @since 1.0.0
 */
@CopilotExperimental
public record DecisionsResult(List<DecisionsAnswer> answers, String model, DecisionsUsage usage) {

    /**
     * Validates and copies the components.
     *
     * @param answers
     *            the answers
     * @param model
     *            the model
     * @param usage
     *            the usage
     */
    public DecisionsResult {
        answers = List.copyOf(answers);
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(usage, "usage");
    }
}
