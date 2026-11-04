/*---------------------------------------------------------------------------------------------
 *  Copyright (c) Microsoft Corporation. All rights reserved.
 *--------------------------------------------------------------------------------------------*/

package com.github.copilot.decisions;

import java.util.List;
import java.util.Objects;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.copilot.CopilotExperimental;

/**
 * One answer in {@link DecisionsResult#answers()}, in question order.
 * {@link #name()} echoes the question name, or is {@code null}.
 *
 * @since 1.0.0
 */
@CopilotExperimental
public sealed interface DecisionsAnswer {

    /**
     * Returns the question name echoed by the API.
     *
     * @return the name, or {@code null}
     */
    String name();

    /**
     * Answer to a predicate question.
     *
     * @param name
     *            the question name, or {@code null}
     * @param probability
     *            the probability that the predicate holds
     */
    record Predicate(String name, double probability) implements DecisionsAnswer {
    }

    /**
     * Answer to a choice question.
     *
     * @param name
     *            the question name, or {@code null}
     * @param choice
     *            the chosen value
     * @param confidence
     *            the confidence in the chosen value
     * @param probabilities
     *            the probability of every choice
     */
    record Choice(String name, DecisionsChoiceValue choice, double confidence,
            List<ChoiceProbability> probabilities) implements DecisionsAnswer {

        /**
         * Validates and copies the components.
         *
         * @param name
         *            the name
         * @param choice
         *            the chosen value
         * @param confidence
         *            the confidence
         * @param probabilities
         *            the probabilities
         */
        public Choice {
            Objects.requireNonNull(choice, "choice");
            probabilities = List.copyOf(probabilities);
        }
    }

    /**
     * Answer to a score question.
     *
     * @param name
     *            the question name, or {@code null}
     * @param score
     *            the expected score
     * @param confidence
     *            the confidence
     * @param probabilities
     *            the probability of every level
     */
    record Score(String name, double score, double confidence,
            List<ScoreProbability> probabilities) implements DecisionsAnswer {

        /**
         * Validates and copies the components.
         *
         * @param name
         *            the name
         * @param score
         *            the score
         * @param confidence
         *            the confidence
         * @param probabilities
         *            the probabilities
         */
        public Score {
            probabilities = List.copyOf(probabilities);
        }
    }

    /**
     * The model refused to answer the question.
     *
     * @param name
     *            the question name, or {@code null}
     */
    record Refusal(String name) implements DecisionsAnswer {
    }

    /**
     * An answer type this SDK version does not recognize.
     *
     * @param name
     *            the question name, or {@code null}
     * @param wireType
     *            the {@code type} value from the wire
     * @param raw
     *            the raw answer object; copied on construction and access
     */
    record Unknown(String name, String wireType, ObjectNode raw) implements DecisionsAnswer {

        /**
         * Validates and copies the components.
         *
         * @param name
         *            the name
         * @param wireType
         *            the wire type
         * @param raw
         *            the raw answer
         */
        public Unknown {
            Objects.requireNonNull(raw, "raw");
            raw = raw.deepCopy();
        }

        /**
         * Returns a copy of the raw answer object.
         *
         * @return the raw answer
         */
        @Override
        public ObjectNode raw() {
            return raw.deepCopy();
        }
    }

    /**
     * Probability of one choice.
     *
     * @param value
     *            the choice value
     * @param probability
     *            the probability
     */
    record ChoiceProbability(DecisionsChoiceValue value, double probability) {

        /**
         * Validates the components.
         *
         * @param value
         *            the value
         * @param probability
         *            the probability
         */
        public ChoiceProbability {
            Objects.requireNonNull(value, "value");
        }
    }

    /**
     * Probability of one score level.
     *
     * @param label
     *            the level label
     * @param value
     *            the level's numeric value
     * @param probability
     *            the probability
     */
    record ScoreProbability(String label, long value, double probability) {

        /**
         * Validates the components.
         *
         * @param label
         *            the label
         * @param value
         *            the value
         * @param probability
         *            the probability
         */
        public ScoreProbability {
            Objects.requireNonNull(label, "label");
        }
    }
}
