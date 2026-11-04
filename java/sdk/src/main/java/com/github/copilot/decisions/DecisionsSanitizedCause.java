/*---------------------------------------------------------------------------------------------
 *  Copyright (c) Microsoft Corporation. All rights reserved.
 *--------------------------------------------------------------------------------------------*/

package com.github.copilot.decisions;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;

import com.github.copilot.CopilotExperimental;

/**
 * Credential-free stand-in for a transport failure, used as the cause of
 * {@link DecisionsErrorKind#NETWORK} errors. Only the original exception's
 * class name, a string {@code code} (when the original exposes a public
 * {@code String getCode()}), and its redacted message are retained; it has no
 * cause and no stack trace, so request URLs and headers cannot leak through it.
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
        String rawMessage = error == null ? "null" : error.getMessage();
        String message = DecisionsRedaction.redact(rawMessage == null ? "" : rawMessage, secrets);
        String name = error == null ? "java.lang.Throwable" : error.getClass().getName();
        return new DecisionsSanitizedCause(name, message, readCode(error));
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
        return name + ": " + getMessage();
    }
}
