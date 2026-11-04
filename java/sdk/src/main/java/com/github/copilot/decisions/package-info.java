/*---------------------------------------------------------------------------------------------
 *  Copyright (c) Microsoft Corporation. All rights reserved.
 *--------------------------------------------------------------------------------------------*/

/**
 * Experimental support for OpenAI's Decisions API ({@code POST /v1/decisions}),
 * called directly against the provider endpoint and credentials a
 * {@link com.github.copilot.CopilotSession} is configured with.
 * <p>
 * Obtain the API through
 * {@link com.github.copilot.CopilotSession#getDecisions()}. The runtime must
 * run with {@code COPILOT_ALLOW_GET_PROVIDER_ENDPOINT=true}.
 *
 * <pre>{@code
 * DecisionsResult result = session.getDecisions()
 * 		.create(new DecisionsRequest().setModel("gpt-6-luna").setInput("The export button does nothing.")
 * 				.setQuestions(List.of(new DecisionsQuestion.Predicate("Is this a product defect?"))))
 * 		.get();
 * }</pre>
 *
 * <p>
 * Every public type in this package is experimental and may change or be
 * removed in a future version.
 *
 * @since 1.0.0
 */
package com.github.copilot.decisions;
