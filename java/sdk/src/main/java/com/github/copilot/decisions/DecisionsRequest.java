/*---------------------------------------------------------------------------------------------
 *  Copyright (c) Microsoft Corporation. All rights reserved.
 *--------------------------------------------------------------------------------------------*/

package com.github.copilot.decisions;

import java.util.List;

import com.github.copilot.CopilotExperimental;

/**
 * A Decisions API request. {@code model}, an input (string or items), and
 * {@code questions} are required; optional values left {@code null} are omitted
 * from the wire request.
 *
 * @since 1.0.0
 */
@CopilotExperimental
public final class DecisionsRequest {

    private String model;
    private String inputText;
    private List<DecisionsInputItem> inputItems;
    private List<DecisionsQuestion> questions;
    private String promptCacheKey;
    private DecisionsPromptCacheOptions promptCacheOptions;
    private String safetyIdentifier;

    /**
     * Creates an empty request.
     */
    public DecisionsRequest() {
    }

    /**
     * Returns the model ID.
     *
     * @return the model, or {@code null}
     */
    public String getModel() {
        return model;
    }

    /**
     * Sets the model ID sent to the Decisions API.
     *
     * @param model
     *            the model
     * @return this request
     */
    public DecisionsRequest setModel(String model) {
        this.model = model;
        return this;
    }

    /**
     * Returns the string input, if the input was set as a string.
     *
     * @return the string input, or {@code null}
     */
    public String getInputText() {
        return inputText;
    }

    /**
     * Returns the input items, if the input was set as a list.
     *
     * @return the input items, or {@code null}
     */
    public List<DecisionsInputItem> getInputItems() {
        return inputItems;
    }

    /**
     * Sets the input as a single string. Clears any input items.
     *
     * @param input
     *            the input text
     * @return this request
     */
    public DecisionsRequest setInput(String input) {
        this.inputText = input;
        this.inputItems = null;
        return this;
    }

    /**
     * Sets the input as a list of conversation items. Clears any string input.
     *
     * @param input
     *            the input items
     * @return this request
     */
    public DecisionsRequest setInput(List<? extends DecisionsInputItem> input) {
        this.inputItems = input == null ? null : List.copyOf(input);
        this.inputText = null;
        return this;
    }

    /**
     * Returns the questions.
     *
     * @return the questions, or {@code null}
     */
    public List<DecisionsQuestion> getQuestions() {
        return questions;
    }

    /**
     * Sets the questions, answered in order.
     *
     * @param questions
     *            the questions
     * @return this request
     */
    public DecisionsRequest setQuestions(List<? extends DecisionsQuestion> questions) {
        this.questions = questions == null ? null : List.copyOf(questions);
        return this;
    }

    /**
     * Returns the prompt cache key.
     *
     * @return the key, or {@code null}
     */
    public String getPromptCacheKey() {
        return promptCacheKey;
    }

    /**
     * Sets the prompt cache key ({@code prompt_cache_key}).
     *
     * @param promptCacheKey
     *            the key
     * @return this request
     */
    public DecisionsRequest setPromptCacheKey(String promptCacheKey) {
        this.promptCacheKey = promptCacheKey;
        return this;
    }

    /**
     * Returns the prompt cache options.
     *
     * @return the options, or {@code null}
     */
    public DecisionsPromptCacheOptions getPromptCacheOptions() {
        return promptCacheOptions;
    }

    /**
     * Sets the prompt cache options ({@code prompt_cache_options}).
     *
     * @param promptCacheOptions
     *            the options
     * @return this request
     */
    public DecisionsRequest setPromptCacheOptions(DecisionsPromptCacheOptions promptCacheOptions) {
        this.promptCacheOptions = promptCacheOptions;
        return this;
    }

    /**
     * Returns the safety identifier.
     *
     * @return the identifier, or {@code null}
     */
    public String getSafetyIdentifier() {
        return safetyIdentifier;
    }

    /**
     * Sets the safety identifier ({@code safety_identifier}).
     *
     * @param safetyIdentifier
     *            the identifier
     * @return this request
     */
    public DecisionsRequest setSafetyIdentifier(String safetyIdentifier) {
        this.safetyIdentifier = safetyIdentifier;
        return this;
    }
}
