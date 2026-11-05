import sdk = require("@github/copilot-sdk");
import extension = require("@github/copilot-sdk/extension");

declare const session: sdk.CopilotSession;
const api: sdk.SessionDecisionsApi = session.decisions;
const request: sdk.DecisionsRequest = {
    model: "package-smoke",
    input: "Classify this offline type-check fixture.",
    questions: [{ type: "predicate", instructions: "Return a boolean." }],
};
const result: Promise<sdk.DecisionsResult> = api.create(request, { timeoutMs: null });
const error: sdk.DecisionsError = new sdk.DecisionsError("http", "offline", { status: 429, retryAfterMs: 25 });
const delay: number | undefined = error.retryAfterMs;
const config: extension.JoinSessionConfig = {};
// @ts-expect-error The public error kind must remain a closed union.
const invalidKind: sdk.DecisionsErrorKind = "not-a-decisions-error";
void [sdk.CopilotClient, extension.joinSession, result, delay, config, invalidKind];
