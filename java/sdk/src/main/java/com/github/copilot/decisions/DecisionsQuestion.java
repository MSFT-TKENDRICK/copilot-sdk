/*---------------------------------------------------------------------------------------------
 *  Copyright (c) Microsoft Corporation. All rights reserved.
 *--------------------------------------------------------------------------------------------*/

package com.github.copilot.decisions;

import java.util.List;
import java.util.Objects;

import com.github.copilot.CopilotExperimental;
import com.github.copilot.decisions.DecisionsContentPart.QuestionPart;

/**
 * A question in {@link DecisionsRequest#getQuestions()}. Optional {@code name}
 * and {@code description} values set to {@code null} are omitted from the wire
 * request.
 *
 * @since 1.0.0
 */
@CopilotExperimental
public sealed interface DecisionsQuestion {

    /**
     * Returns the question instructions.
     *
     * @return the instructions
     */
    DecisionsContent<QuestionPart> instructions();

    /**
     * Returns the optional question name echoed on the answer.
     *
     * @return the name, or {@code null}
     */
    String name();

    /**
     * A yes/no question answered with a probability ({@code predicate}).
     *
     * @param instructions
     *            the instructions
     * @param name
     *            optional name, or {@code null}
     */
    record Predicate(DecisionsContent<QuestionPart> instructions, String name) implements DecisionsQuestion {

        /**
         * Validates the components.
         *
         * @param instructions
         *            the instructions
         * @param name
         *            optional name
         */
        public Predicate {
            Objects.requireNonNull(instructions, "instructions");
        }

        /**
         * Creates an unnamed predicate question with string instructions.
         *
         * @param instructions
         *            the instructions
         */
        public Predicate(String instructions) {
            this(DecisionsContent.of(instructions), null);
        }

        /**
         * Creates a named predicate question with string instructions.
         *
         * @param name
         *            the name
         * @param instructions
         *            the instructions
         */
        public Predicate(String name, String instructions) {
            this(DecisionsContent.of(instructions), name);
        }
    }

    /**
     * A question answered by picking one of {@code choices} ({@code choice}).
     *
     * @param instructions
     *            the instructions
     * @param name
     *            optional name, or {@code null}
     * @param choices
     *            the available choices
     */
    record Choice(DecisionsContent<QuestionPart> instructions, String name,
            List<ChoiceOption> choices) implements DecisionsQuestion {

        /**
         * Validates and copies the components.
         *
         * @param instructions
         *            the instructions
         * @param name
         *            optional name
         * @param choices
         *            the choices
         */
        public Choice {
            Objects.requireNonNull(instructions, "instructions");
            choices = List.copyOf(choices);
        }

        /**
         * Creates a choice question with string instructions.
         *
         * @param name
         *            optional name, or {@code null}
         * @param instructions
         *            the instructions
         * @param choices
         *            the choices
         */
        public Choice(String name, String instructions, List<ChoiceOption> choices) {
            this(DecisionsContent.of(instructions), name, choices);
        }
    }

    /**
     * A question answered on an ordered scale of {@code levels} ({@code score}).
     *
     * @param instructions
     *            the instructions
     * @param name
     *            optional name, or {@code null}
     * @param levels
     *            the levels, lowest first
     */
    record Score(DecisionsContent<QuestionPart> instructions, String name,
            List<ScoreLevel> levels) implements DecisionsQuestion {

        /**
         * Validates and copies the components.
         *
         * @param instructions
         *            the instructions
         * @param name
         *            optional name
         * @param levels
         *            the levels
         */
        public Score {
            Objects.requireNonNull(instructions, "instructions");
            levels = List.copyOf(levels);
        }

        /**
         * Creates a score question with string instructions.
         *
         * @param name
         *            optional name, or {@code null}
         * @param instructions
         *            the instructions
         * @param levels
         *            the levels
         */
        public Score(String name, String instructions, List<ScoreLevel> levels) {
            this(DecisionsContent.of(instructions), name, levels);
        }
    }

    /**
     * One choice of a {@link Choice} question.
     *
     * @param value
     *            the string or boolean value
     * @param description
     *            optional description, or {@code null}
     */
    record ChoiceOption(DecisionsChoiceValue value, String description) {

        /**
         * Validates the components.
         *
         * @param value
         *            the value
         * @param description
         *            optional description
         */
        public ChoiceOption {
            Objects.requireNonNull(value, "value");
        }

        /**
         * Creates a string choice without a description.
         *
         * @param value
         *            the value
         */
        public ChoiceOption(String value) {
            this(DecisionsChoiceValue.of(value), null);
        }

        /**
         * Creates a boolean choice without a description.
         *
         * @param value
         *            the value
         */
        public ChoiceOption(boolean value) {
            this(DecisionsChoiceValue.of(value), null);
        }

        /**
         * Creates a string choice with a description.
         *
         * @param value
         *            the value
         * @param description
         *            the description
         */
        public ChoiceOption(String value, String description) {
            this(DecisionsChoiceValue.of(value), description);
        }

        /**
         * Creates a boolean choice with a description.
         *
         * @param value
         *            the value
         * @param description
         *            the description
         */
        public ChoiceOption(boolean value, String description) {
            this(DecisionsChoiceValue.of(value), description);
        }
    }

    /**
     * One level of a {@link Score} question.
     *
     * @param label
     *            the label
     * @param description
     *            optional description, or {@code null}
     */
    record ScoreLevel(String label, String description) {

        /**
         * Validates the components.
         *
         * @param label
         *            the label
         * @param description
         *            optional description
         */
        public ScoreLevel {
            Objects.requireNonNull(label, "label");
        }

        /**
         * Creates a level without a description.
         *
         * @param label
         *            the label
         */
        public ScoreLevel(String label) {
            this(label, null);
        }
    }
}
