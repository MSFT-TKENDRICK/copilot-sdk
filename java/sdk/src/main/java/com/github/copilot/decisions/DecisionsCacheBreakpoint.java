/*---------------------------------------------------------------------------------------------
 *  Copyright (c) Microsoft Corporation. All rights reserved.
 *--------------------------------------------------------------------------------------------*/

package com.github.copilot.decisions;

import java.util.Objects;

import com.github.copilot.CopilotExperimental;

/**
 * Marks the end of an explicit prompt-cache segment
 * ({@code prompt_cache_breakpoint} on the wire).
 *
 * @param mode
 *            the breakpoint mode; currently always {@code "explicit"}
 * @since 1.0.0
 */
@CopilotExperimental
public record DecisionsCacheBreakpoint(String mode) {

    /**
     * Validates the components.
     *
     * @param mode
     *            the breakpoint mode
     */
    public DecisionsCacheBreakpoint {
        Objects.requireNonNull(mode, "mode");
    }

    /**
     * Returns an explicit cache breakpoint ({@code {"mode":"explicit"}}).
     *
     * @return an explicit breakpoint
     */
    public static DecisionsCacheBreakpoint explicit() {
        return new DecisionsCacheBreakpoint("explicit");
    }
}
