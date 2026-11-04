/*---------------------------------------------------------------------------------------------
 *  Copyright (c) Microsoft Corporation. All rights reserved.
 *--------------------------------------------------------------------------------------------*/

package com.github.copilot.decisions;

import java.util.Objects;

import com.github.copilot.CopilotExperimental;

/**
 * A choice value, which is either a string or a boolean on the wire. The two
 * forms are distinct: {@code "true"} and {@code true} are different choices.
 *
 * @since 1.0.0
 */
@CopilotExperimental
public sealed interface DecisionsChoiceValue {

    /**
     * Creates a string choice value.
     *
     * @param value
     *            the string
     * @return the choice value
     */
    static DecisionsChoiceValue of(String value) {
        return new StringValue(value);
    }

    /**
     * Creates a boolean choice value.
     *
     * @param value
     *            the boolean
     * @return the choice value
     */
    static DecisionsChoiceValue of(boolean value) {
        return new BooleanValue(value);
    }

    /**
     * A string choice value.
     *
     * @param value
     *            the string
     */
    record StringValue(String value) implements DecisionsChoiceValue {

        /**
         * Validates the components.
         *
         * @param value
         *            the string
         */
        public StringValue {
            Objects.requireNonNull(value, "value");
        }

        @Override
        public String toString() {
            return value;
        }
    }

    /**
     * A boolean choice value.
     *
     * @param value
     *            the boolean
     */
    record BooleanValue(boolean value) implements DecisionsChoiceValue {

        @Override
        public String toString() {
            return Boolean.toString(value);
        }
    }
}
