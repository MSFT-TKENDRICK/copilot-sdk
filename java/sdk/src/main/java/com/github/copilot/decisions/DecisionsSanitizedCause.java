/*---------------------------------------------------------------------------------------------
 *  Copyright (c) Microsoft Corporation. All rights reserved.
 *--------------------------------------------------------------------------------------------*/

package com.github.copilot.decisions;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;

import com.github.copilot.CopilotExperimental;

/**
 * Bounded, credential-redacted stand-in for a transport failure, used as the
 * cause of {@link DecisionsErrorKind#NETWORK} errors. Only the original
 * exception's class name, a string {@code code} (when the original exposes a
 * public {@code String getCode()}), and its redacted message are retained; it
 * has no cause and no stack trace. Arbitrary URL/header attributes are not
 * retained.
 *
 * @since 1.0.0
 */
@CopilotExperimental
public final class DecisionsSanitizedCause extends Exception {

    private static final long serialVersionUID = 1L;

    private final String name;
    private final String code;

    DecisionsSanitizedCause(String name, String message, String code) {
        super(message, null, false, false);
        this.name = name;
        this.code = code;
    }

    static DecisionsSanitizedCause of(Throwable error, List<String> secrets) {
        String rawMessage;
        try {
            rawMessage = error == null ? "" : error.getMessage();
        } catch (RuntimeException e) {
            rawMessage = "Transport failure details were unavailable.";
        }
        String message = DecisionsRedaction.sanitize(rawMessage == null ? "" : rawMessage, secrets);
        String name = error == null ? "java.lang.Throwable" : error.getClass().getName();
        return new DecisionsSanitizedCause(DecisionsRedaction.sanitize(name, secrets), message,
                DecisionsRedaction.sanitize(readCode(error), secrets));
    }

    private static String readCode(Throwable error) {
        if (error == null) {
            return null;
        }
        try {
            Method method = error.getClass().getMethod("getCode");
            if (method.getReturnType() == String.class && Modifier.isPublic(method.getModifiers())
                    && Modifier.isPublic(method.getDeclaringClass().getModifiers())) {
                return (String) method.invoke(error);
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
        return null;
    }

    /**
     * Returns the fully qualified class name of the original exception.
     *
     * @return the original exception's class name
     */
    public String getName() {
        return name;
    }

    /**
     * Returns the original exception's string code, if it had one.
     *
     * @return the code, or {@code null}
     */
    public String getCode() {
        return code;
    }

    @Override
    public String toString() {
        return DecisionsRedaction.capUtf8(name + ": " + getMessage(), SessionDecisions.MAX_ERROR_BODY_BYTES);
    }
}
