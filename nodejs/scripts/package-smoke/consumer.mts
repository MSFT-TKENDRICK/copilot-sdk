import {
    CopilotClient, DecisionsError, type CopilotSession, type DecisionsErrorKind,
    type DecisionsRequest, type DecisionsResult, type SessionDecisionsApi,
} from "@github/copilot-sdk";
import { joinSession, type JoinSessionConfig } from "@github/copilot-sdk/extension";

declare const session: CopilotSession;
const api: SessionDecisionsApi = session.decisions;
const request: DecisionsRequest = {
    model: "package-smoke",
    input: "Classify this offline type-check fixture.",
    questions: [{ type: "predicate", instructions: [{ type: "input_json", data: { rule: "boolean" } }] }],
};
const result: Promise<DecisionsResult> = api.create(request, { timeoutMs: 1_000, signal: new AbortController().signal });
const error: DecisionsError = new DecisionsError("timeout", "offline");
const kind: DecisionsErrorKind = error.kind;
const config: JoinSessionConfig = {};
// @ts-expect-error The public error kind must remain a closed union.
const invalidKind: DecisionsErrorKind = "not-a-decisions-error";
void [CopilotClient, joinSession, result, kind, config, invalidKind];
