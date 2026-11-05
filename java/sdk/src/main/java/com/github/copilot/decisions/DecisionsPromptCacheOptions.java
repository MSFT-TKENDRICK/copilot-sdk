/*---------------------------------------------------------------------------------------------
 *  Copyright (c) Microsoft Corporation. All rights reserved.
 *--------------------------------------------------------------------------------------------*/

package com.github.copilot.decisions;

import com.github.copilot.CopilotExperimental;

/**
 * Prompt-cache options ({@code prompt_cache_options} on the wire). Components
 * set to {@code null} are omitted.
 *
 * @param mode
 *            optional mode: {@code "implicit"} or {@code "explicit"}
 * @param ttl
 *            optional time-to-live: {@code "30m"} is the documented value
 * @since 1.0.0
 */
@CopilotExperimental
public record DecisionsPromptCacheOptions(String mode, String ttl) {
}
