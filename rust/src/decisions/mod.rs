//! **Experimental.** Client for the provider Decisions API (`POST {baseUrl}/decisions`).
//!
//! The Decisions API scores a fixed set of questions (predicates, choices, and
//! scores) against an input conversation using the session's current model
//! provider. Obtain the API group with
//! [`Session::decisions`](crate::session::Session::decisions).
//!
//! # Requirements
//!
//! The runtime must expose its provider endpoint, which requires
//! `COPILOT_ALLOW_GET_PROVIDER_ENDPOINT=true` in the runtime's environment (for
//! in-process runtimes, in the host process environment before the runtime is
//! loaded). Only `openai` and `azure` providers over HTTP are supported.
//!
//! # Behavior
//!
//! - `session.provider.getEndpoint` is called on every [`create`](crate::decisions::SessionDecisions::create)
//!   call. On an HTTP 401 or 403 response it is called once more; the request is
//!   retried a single time only when the effective URL or credentials changed.
//!   A retried request may be billed twice by the provider.
//! - The built-in client never follows redirects. A 3xx response is reported as a
//!   [`DecisionsErrorKind::Http`](crate::decisions::DecisionsErrorKind::Http) error.
//!   Injected clients are trusted to enforce their own redirect and TLS policy.
//! - Requests are sent directly to the provider; they are not routed through a
//!   [`CopilotRequestHandler`](crate::copilot_request_handler::CopilotRequestHandler).
//! - Known endpoint credentials of every nonempty length are redacted from
//!   retained diagnostics, including common JSON and URL encodings. Other
//!   header and query values use an eight-Unicode-scalar heuristic. This is
//!   not general PII removal or protection against arbitrary transformations.
//! - Error bodies are capped at 64 KiB and success bodies at 8 MiB.
//! - The default 600-second deadline covers the entire call, including endpoint
//!   resolution, refresh, both HTTP attempts, and body reads. Override or disable
//!   it with [`SessionDecisions::create_with_timeout`].
//!
//! # Cancellation
//!
//! Cancel a call by dropping its future, for example by racing it against the
//! session's [`cancellation_token`](crate::session::Session::cancellation_token)
//! in `tokio::select!`. Dropping the future aborts the in-flight HTTP request.
//! The `getEndpoint` RPC itself is not cancelled on the runtime side.
//!
//! # Errors
//!
//! Failures are reported as [`Error`](crate::Error) values whose kind is
//! [`ErrorKind::Decisions`](crate::ErrorKind::Decisions); use
//! [`Error::decisions_error`](crate::Error::decisions_error) for the details.
//! A request with an empty `model` is rejected with
//! [`ErrorKind::InvalidConfig`](crate::ErrorKind::InvalidConfig) before any I/O.

use std::fmt;
use std::future::Future;
use std::time::Duration;

use bytes::Bytes;
use futures_util::future::BoxFuture;
use futures_util::stream::{BoxStream, StreamExt};
use indexmap::IndexMap;
use serde::{Deserialize, Serialize, Serializer};
use serde_json::{Map, Value};

use crate::rpc::{
    ProviderEndpoint, ProviderEndpointTransport, ProviderEndpointType, ProviderGetEndpointRequest,
};
use crate::session::Session;
use crate::{Error, ErrorKind};

mod retry_after;
#[cfg(test)]
mod tests;

type BoxError = Box<dyn std::error::Error + Send + Sync>;

const GATE_ENV: &str = "COPILOT_ALLOW_GET_PROVIDER_ENDPOINT";
const MAX_ERROR_BODY_BYTES: usize = 64 * 1024;
const MAX_RESPONSE_BYTES: usize = 8 * 1024 * 1024;
const MIN_SECRET_LENGTH: usize = 8;
const REDACTED: &str = "[REDACTED]";
const DEFAULT_TIMEOUT: Duration = Duration::from_secs(600);
const MAX_SAFE_INTEGER: i64 = 9_007_199_254_740_991;

// ============================================================================
// Request types
// ============================================================================

/// **Experimental.** Marks the end of an explicit prompt-cache segment.
///
/// Serializes as `{"mode":"explicit"}`.
#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
pub struct DecisionsCacheBreakpoint;

impl Serialize for DecisionsCacheBreakpoint {
    fn serialize<S: Serializer>(&self, serializer: S) -> Result<S::Ok, S::Error> {
        use serde::ser::SerializeMap;
        let mut map = serializer.serialize_map(Some(1))?;
        map.serialize_entry("mode", "explicit")?;
        map.end()
    }
}

/// **Experimental.** Image detail level.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
#[serde(rename_all = "snake_case")]
pub enum DecisionsImageDetail {
    /// `low`
    Low,
    /// `high`
    High,
    /// `auto`
    Auto,
    /// `original`
    Original,
}

/// **Experimental.** Status of a conversation item.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
#[serde(rename_all = "snake_case")]
pub enum DecisionsItemStatus {
    /// `in_progress`
    InProgress,
    /// `completed`
    Completed,
    /// `incomplete`
    Incomplete,
}

/// **Experimental.** Phase of an assistant message.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
#[serde(rename_all = "snake_case")]
pub enum DecisionsMessagePhase {
    /// `commentary`
    Commentary,
    /// `final_answer`
    FinalAnswer,
}

/// **Experimental.** Text content part (`input_text`).
#[derive(Debug, Clone, PartialEq, Serialize)]
#[serde(tag = "type", rename = "input_text")]
pub struct DecisionsInputText {
    /// The text.
    pub text: String,
    /// Ends an explicit prompt-cache segment after this part.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub prompt_cache_breakpoint: Option<DecisionsCacheBreakpoint>,
}

impl DecisionsInputText {
    /// **Experimental.** A text part without a cache breakpoint.
    pub fn new(text: impl Into<String>) -> Self {
        Self {
            text: text.into(),
            prompt_cache_breakpoint: None,
        }
    }
}

/// **Experimental.** Image content part (`input_image`). `image_url` is a data
/// URL or an `http(s)` URL.
#[derive(Debug, Clone, PartialEq, Serialize)]
#[serde(tag = "type", rename = "input_image")]
pub struct DecisionsInputImage {
    /// Data URL or `http(s)` URL of the image.
    pub image_url: String,
    /// Image detail level.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub detail: Option<DecisionsImageDetail>,
    /// Ends an explicit prompt-cache segment after this part.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub prompt_cache_breakpoint: Option<DecisionsCacheBreakpoint>,
}

/// **Experimental.** Structured JSON content part (`input_json`). `data` is a
/// JSON object or an array of objects.
#[derive(Debug, Clone, PartialEq, Serialize)]
#[serde(tag = "type", rename = "input_json")]
pub struct DecisionsInputJson {
    /// The JSON payload.
    pub data: Value,
}

/// **Experimental.** Assistant text content part (`output_text`).
#[derive(Debug, Clone, PartialEq, Serialize)]
#[serde(tag = "type", rename = "output_text")]
pub struct DecisionsOutputText {
    /// The text.
    pub text: String,
}

/// **Experimental.** Assistant refusal content part (`refusal`).
#[derive(Debug, Clone, PartialEq, Serialize)]
#[serde(tag = "type", rename = "refusal")]
pub struct DecisionsRefusalContent {
    /// The refusal text.
    pub refusal: String,
}

/// **Experimental.** Content that is either a plain string or a list of parts.
#[derive(Debug, Clone, PartialEq, Serialize)]
#[serde(untagged)]
pub enum DecisionsContent<P> {
    /// Plain text content.
    Text(String),
    /// A list of content parts.
    Parts(Vec<P>),
}

impl<P> From<&str> for DecisionsContent<P> {
    fn from(value: &str) -> Self {
        Self::Text(value.to_owned())
    }
}

impl<P> From<String> for DecisionsContent<P> {
    fn from(value: String) -> Self {
        Self::Text(value)
    }
}

impl<P> From<Vec<P>> for DecisionsContent<P> {
    fn from(value: Vec<P>) -> Self {
        Self::Parts(value)
    }
}

/// **Experimental.** Content part of a user message.
#[derive(Debug, Clone, PartialEq, Serialize)]
#[serde(untagged)]
pub enum DecisionsUserContentPart {
    /// `input_text`
    Text(DecisionsInputText),
    /// `input_image`
    Image(DecisionsInputImage),
    /// `input_json`
    Json(DecisionsInputJson),
}

/// **Experimental.** Content part of an assistant message.
#[derive(Debug, Clone, PartialEq, Serialize)]
#[serde(untagged)]
pub enum DecisionsAssistantContentPart {
    /// `output_text`
    Text(DecisionsOutputText),
    /// `refusal`
    Refusal(DecisionsRefusalContent),
}

/// **Experimental.** Content part of a tool or function call output.
#[derive(Debug, Clone, PartialEq, Serialize)]
#[serde(untagged)]
pub enum DecisionsToolOutputPart {
    /// `input_text`
    Text(DecisionsInputText),
    /// `input_image`
    Image(DecisionsInputImage),
}

/// **Experimental.** A conversation message, tagged by `role`.
#[derive(Debug, Clone, PartialEq, Serialize)]
#[serde(tag = "role", rename_all = "snake_case")]
pub enum DecisionsMessage {
    /// `role: "user"`
    User {
        /// Message content.
        content: DecisionsContent<DecisionsUserContentPart>,
    },
    /// `role: "developer"`
    Developer {
        /// Message content.
        content: DecisionsContent<DecisionsInputText>,
    },
    /// `role: "system"`
    System {
        /// Message content.
        content: DecisionsContent<DecisionsInputText>,
    },
    /// `role: "assistant"`
    Assistant {
        /// Message content.
        content: DecisionsContent<DecisionsAssistantContentPart>,
        /// Assistant message phase.
        #[serde(skip_serializing_if = "Option::is_none")]
        phase: Option<DecisionsMessagePhase>,
    },
}

/// **Experimental.** A `function_call` input item.
#[derive(Debug, Clone, PartialEq, Serialize)]
#[serde(tag = "type", rename = "function_call")]
pub struct DecisionsFunctionCall {
    /// Call identifier.
    pub call_id: String,
    /// Function name.
    pub name: String,
    /// JSON-encoded arguments.
    pub arguments: String,
    /// Item identifier.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub id: Option<String>,
    /// Function namespace.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub namespace: Option<String>,
    /// Item status.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub status: Option<DecisionsItemStatus>,
}

/// **Experimental.** A `function_call_output` input item.
#[derive(Debug, Clone, PartialEq, Serialize)]
#[serde(tag = "type", rename = "function_call_output")]
pub struct DecisionsFunctionCallOutput {
    /// Call identifier.
    pub call_id: String,
    /// Call output.
    pub output: DecisionsContent<DecisionsToolOutputPart>,
    /// Item identifier.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub id: Option<String>,
    /// Item status.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub status: Option<DecisionsItemStatus>,
}

/// **Experimental.** A `custom_tool_call` input item.
#[derive(Debug, Clone, PartialEq, Serialize)]
#[serde(tag = "type", rename = "custom_tool_call")]
pub struct DecisionsCustomToolCall {
    /// Call identifier.
    pub call_id: String,
    /// Tool name.
    pub name: String,
    /// Raw tool input.
    pub input: String,
    /// Item identifier.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub id: Option<String>,
    /// Tool namespace.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub namespace: Option<String>,
    /// Item status.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub status: Option<DecisionsItemStatus>,
}

/// **Experimental.** A `custom_tool_call_output` input item.
#[derive(Debug, Clone, PartialEq, Serialize)]
#[serde(tag = "type", rename = "custom_tool_call_output")]
pub struct DecisionsCustomToolCallOutput {
    /// Call identifier.
    pub call_id: String,
    /// Call output.
    pub output: DecisionsContent<DecisionsToolOutputPart>,
    /// Item identifier.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub id: Option<String>,
    /// Item status.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub status: Option<DecisionsItemStatus>,
}

/// **Experimental.** A conversation input item.
#[derive(Debug, Clone, PartialEq)]
pub enum DecisionsInputItem {
    /// A message serialized with `"type": "message"`.
    Message(DecisionsMessage),
    /// A message serialized without a `type` field (the "easy input" form).
    UntypedMessage(DecisionsMessage),
    /// A `function_call` item.
    FunctionCall(DecisionsFunctionCall),
    /// A `function_call_output` item.
    FunctionCallOutput(DecisionsFunctionCallOutput),
    /// A `custom_tool_call` item.
    CustomToolCall(DecisionsCustomToolCall),
    /// A `custom_tool_call_output` item.
    CustomToolCallOutput(DecisionsCustomToolCallOutput),
    /// An item sent verbatim (already in wire format). Use for item types this
    /// SDK version does not model yet.
    Raw(Map<String, Value>),
}

impl Serialize for DecisionsInputItem {
    fn serialize<S: Serializer>(&self, serializer: S) -> Result<S::Ok, S::Error> {
        #[derive(Serialize)]
        struct Typed<'a> {
            r#type: &'static str,
            #[serde(flatten)]
            message: &'a DecisionsMessage,
        }
        match self {
            Self::Message(message) => Typed {
                r#type: "message",
                message,
            }
            .serialize(serializer),
            Self::UntypedMessage(message) => message.serialize(serializer),
            Self::FunctionCall(item) => item.serialize(serializer),
            Self::FunctionCallOutput(item) => item.serialize(serializer),
            Self::CustomToolCall(item) => item.serialize(serializer),
            Self::CustomToolCallOutput(item) => item.serialize(serializer),
            Self::Raw(raw) => raw.serialize(serializer),
        }
    }
}

/// **Experimental.** Request input: a plain string or a list of items.
#[derive(Debug, Clone, PartialEq, Serialize)]
#[serde(untagged)]
pub enum DecisionsInput {
    /// Plain text input.
    Text(String),
    /// Conversation items.
    Items(Vec<DecisionsInputItem>),
}

impl From<&str> for DecisionsInput {
    fn from(value: &str) -> Self {
        Self::Text(value.to_owned())
    }
}

impl From<String> for DecisionsInput {
    fn from(value: String) -> Self {
        Self::Text(value)
    }
}

impl From<Vec<DecisionsInputItem>> for DecisionsInput {
    fn from(value: Vec<DecisionsInputItem>) -> Self {
        Self::Items(value)
    }
}

/// **Experimental.** Text part of question instructions (no cache breakpoint).
#[derive(Debug, Clone, PartialEq, Serialize)]
#[serde(tag = "type", rename = "input_text")]
pub struct DecisionsQuestionText {
    /// The text.
    pub text: String,
}

/// **Experimental.** Image part of question instructions (no cache breakpoint).
#[derive(Debug, Clone, PartialEq, Serialize)]
#[serde(tag = "type", rename = "input_image")]
pub struct DecisionsQuestionImage {
    /// Data URL or `http(s)` URL of the image.
    pub image_url: String,
    /// Image detail level.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub detail: Option<DecisionsImageDetail>,
}

/// **Experimental.** Content part allowed in question instructions.
#[derive(Debug, Clone, PartialEq, Serialize)]
#[serde(untagged)]
pub enum DecisionsQuestionPart {
    /// `input_text`
    Text(DecisionsQuestionText),
    /// `input_image`
    Image(DecisionsQuestionImage),
    /// `input_json` (no cache breakpoint).
    Json(DecisionsInputJson),
}

/// **Experimental.** Question instructions: a string or a list of parts.
pub type DecisionsInstructions = DecisionsContent<DecisionsQuestionPart>;

/// **Experimental.** A choice value: a string or a boolean.
#[derive(Debug, Clone, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(untagged)]
pub enum DecisionsChoiceValue {
    /// Boolean value.
    Bool(bool),
    /// String value.
    String(String),
}

impl From<bool> for DecisionsChoiceValue {
    fn from(value: bool) -> Self {
        Self::Bool(value)
    }
}

impl From<&str> for DecisionsChoiceValue {
    fn from(value: &str) -> Self {
        Self::String(value.to_owned())
    }
}

impl From<String> for DecisionsChoiceValue {
    fn from(value: String) -> Self {
        Self::String(value)
    }
}

/// **Experimental.** A predicate (yes/no probability) question.
#[derive(Debug, Clone, PartialEq, Serialize)]
pub struct DecisionsPredicateQuestion {
    /// Optional name echoed on the answer.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub name: Option<String>,
    /// Question instructions.
    pub instructions: DecisionsInstructions,
}

/// **Experimental.** One option of a choice question.
#[derive(Debug, Clone, PartialEq, Serialize)]
pub struct DecisionsChoiceOption {
    /// Option value.
    pub value: DecisionsChoiceValue,
    /// Option description.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub description: Option<String>,
}

/// **Experimental.** A choice question.
#[derive(Debug, Clone, PartialEq, Serialize)]
pub struct DecisionsChoiceQuestion {
    /// Optional name echoed on the answer.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub name: Option<String>,
    /// Question instructions.
    pub instructions: DecisionsInstructions,
    /// Available options.
    pub choices: Vec<DecisionsChoiceOption>,
}

/// **Experimental.** One level of a score question.
#[derive(Debug, Clone, PartialEq, Serialize)]
pub struct DecisionsScoreLevel {
    /// Level label.
    pub label: String,
    /// Level description.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub description: Option<String>,
}

/// **Experimental.** A score question over ordered levels.
#[derive(Debug, Clone, PartialEq, Serialize)]
pub struct DecisionsScoreQuestion {
    /// Optional name echoed on the answer.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub name: Option<String>,
    /// Question instructions.
    pub instructions: DecisionsInstructions,
    /// Ordered levels; the answer's level `value` is the index in this list.
    pub levels: Vec<DecisionsScoreLevel>,
}

/// **Experimental.** A question, tagged by `type`.
#[derive(Debug, Clone, PartialEq, Serialize)]
#[serde(tag = "type", rename_all = "snake_case")]
pub enum DecisionsQuestion {
    /// `predicate`
    Predicate(DecisionsPredicateQuestion),
    /// `choice`
    Choice(DecisionsChoiceQuestion),
    /// `score`
    Score(DecisionsScoreQuestion),
}

/// **Experimental.** Prompt-cache mode.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
#[serde(rename_all = "snake_case")]
pub enum DecisionsPromptCacheMode {
    /// `implicit`
    Implicit,
    /// `explicit`
    Explicit,
}

/// **Experimental.** Prompt-cache time to live.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
pub enum DecisionsPromptCacheTtl {
    /// `30m`
    #[serde(rename = "30m")]
    ThirtyMinutes,
}

/// **Experimental.** Prompt-cache options. A default value serializes as `{}`.
#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize)]
pub struct DecisionsPromptCacheOptions {
    /// Cache mode.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub mode: Option<DecisionsPromptCacheMode>,
    /// Cache time to live.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub ttl: Option<DecisionsPromptCacheTtl>,
}

/// **Experimental.** A Decisions API request. `None` fields are omitted from
/// the wire request.
#[derive(Debug, Clone, PartialEq, Serialize)]
pub struct DecisionsRequest {
    /// Model to send on the wire (for Azure, the deployment name). Required.
    pub model: String,
    /// The conversation to evaluate.
    pub input: DecisionsInput,
    /// 1 to 400 questions (not validated client-side).
    pub questions: Vec<DecisionsQuestion>,
    /// Prompt-cache key.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub prompt_cache_key: Option<String>,
    /// Prompt-cache options.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub prompt_cache_options: Option<DecisionsPromptCacheOptions>,
    /// Opaque, stable identifier for the end user, used for abuse detection.
    /// Never send an email address, username, or credential; hash or map it
    /// first.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub safety_identifier: Option<String>,
}

impl DecisionsRequest {
    /// **Experimental.** A request with no optional fields set.
    pub fn new(
        model: impl Into<String>,
        input: impl Into<DecisionsInput>,
        questions: Vec<DecisionsQuestion>,
    ) -> Self {
        Self {
            model: model.into(),
            input: input.into(),
            questions,
            prompt_cache_key: None,
            prompt_cache_options: None,
            safety_identifier: None,
        }
    }
}

/// **Experimental.** Per-call options for [`SessionDecisions::create_with_options`].
#[derive(Debug, Clone, Default)]
pub struct DecisionsOptions {
    /// Passed verbatim as `modelId` to `session.provider.getEndpoint`. `None`
    /// uses the session's current endpoint. Never derived from
    /// [`DecisionsRequest::model`].
    pub endpoint_model_id: Option<String>,
    /// Custom HTTP client (proxying, custom TLS). Injected clients are trusted
    /// to enforce their own TLS and redirect policy: build them with
    /// [`reqwest::redirect::Policy::none`]. `None` uses a shared client that
    /// never follows redirects.
    pub http_client: Option<reqwest::Client>,
}

// ============================================================================
// Response types
// ============================================================================

/// **Experimental.** Answer to a predicate question.
#[derive(Debug, Clone, PartialEq)]
pub struct DecisionsPredicateAnswer {
    /// The question's name, if any.
    pub name: Option<String>,
    /// Probability that the predicate holds.
    pub probability: f64,
}

/// **Experimental.** Probability of one choice option.
#[derive(Debug, Clone, PartialEq)]
pub struct DecisionsChoiceProbability {
    /// Option value.
    pub value: DecisionsChoiceValue,
    /// Probability of the option.
    pub probability: f64,
}

/// **Experimental.** Answer to a choice question.
#[derive(Debug, Clone, PartialEq)]
pub struct DecisionsChoiceAnswer {
    /// The question's name, if any.
    pub name: Option<String>,
    /// The selected option value.
    pub choice: DecisionsChoiceValue,
    /// Confidence in the selected option.
    pub confidence: f64,
    /// Per-option probabilities.
    pub probabilities: Vec<DecisionsChoiceProbability>,
}

/// **Experimental.** Probability of one score level.
#[derive(Debug, Clone, PartialEq)]
pub struct DecisionsScoreProbability {
    /// Level label.
    pub label: String,
    /// Integer index of the level.
    pub value: i64,
    /// Probability of the level.
    pub probability: f64,
}

/// **Experimental.** Answer to a score question.
#[derive(Debug, Clone, PartialEq)]
pub struct DecisionsScoreAnswer {
    /// The question's name, if any.
    pub name: Option<String>,
    /// Probability-weighted level index (fractional).
    pub score: f64,
    /// Confidence in the score.
    pub confidence: f64,
    /// Per-level probabilities.
    pub probabilities: Vec<DecisionsScoreProbability>,
}

/// **Experimental.** The model refused to answer the question.
#[derive(Debug, Clone, PartialEq)]
pub struct DecisionsRefusalAnswer {
    /// The question's name, if any.
    pub name: Option<String>,
}

/// **Experimental.** One answer, in question order.
#[derive(Debug, Clone, PartialEq)]
#[non_exhaustive]
pub enum DecisionsAnswer {
    /// Answer to a predicate question.
    Predicate(DecisionsPredicateAnswer),
    /// Answer to a choice question.
    Choice(DecisionsChoiceAnswer),
    /// Answer to a score question.
    Score(DecisionsScoreAnswer),
    /// The model refused to answer.
    Refusal(DecisionsRefusalAnswer),
    /// An answer whose `type` this SDK version does not recognize.
    Unknown {
        /// The answer's `name`, if any.
        name: Option<String>,
        /// The original wire `type`.
        wire_type: String,
        /// The original JSON object.
        raw: Value,
    },
}

impl DecisionsAnswer {
    /// **Experimental.** The answer's question name, if any.
    pub fn name(&self) -> Option<&str> {
        match self {
            Self::Predicate(a) => a.name.as_deref(),
            Self::Choice(a) => a.name.as_deref(),
            Self::Score(a) => a.name.as_deref(),
            Self::Refusal(a) => a.name.as_deref(),
            Self::Unknown { name, .. } => name.as_deref(),
        }
    }
}

/// **Experimental.** Input token details.
#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
pub struct DecisionsInputTokensDetails {
    /// Tokens served from the prompt cache.
    pub cached_tokens: i64,
    /// Tokens written to the prompt cache.
    pub cache_write_tokens: i64,
}

/// **Experimental.** Output token details.
#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
pub struct DecisionsOutputTokensDetails {
    /// Reasoning tokens.
    pub reasoning_tokens: i64,
}

/// **Experimental.** Token usage. Missing counts are reported as `0`.
/// Signed provider values, including negative sentinels, are preserved.
#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
pub struct DecisionsUsage {
    /// Input tokens.
    pub input_tokens: i64,
    /// Input token details.
    pub input_tokens_details: DecisionsInputTokensDetails,
    /// Output tokens.
    pub output_tokens: i64,
    /// Output token details.
    pub output_tokens_details: DecisionsOutputTokensDetails,
    /// Total tokens.
    pub total_tokens: i64,
}

/// **Experimental.** A Decisions API result.
#[derive(Debug, Clone, PartialEq)]
pub struct DecisionsResult {
    /// One answer per question, in question order.
    pub answers: Vec<DecisionsAnswer>,
    /// The model that produced the answers.
    pub model: String,
    /// Token usage.
    pub usage: DecisionsUsage,
}

// ============================================================================
// Errors
// ============================================================================

/// **Experimental.** Category of a [`DecisionsError`].
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
#[non_exhaustive]
pub enum DecisionsErrorKind {
    /// The runtime could not provide the session's provider endpoint.
    EndpointUnavailable,
    /// The provider endpoint has an invalid URL or headers.
    InvalidEndpoint,
    /// The session's provider type does not support the Decisions API.
    UnsupportedProvider,
    /// The session's provider transport is not HTTP.
    UnsupportedTransport,
    /// The provider session token is bound to a different model.
    ModelMismatch,
    /// The provider session token uses the same header as the API key.
    CredentialConflict,
    /// The provider returned a non-2xx HTTP status.
    Http,
    /// The provider returned an unexpected success response.
    InvalidResponse,
    /// The request could not be sent or the response could not be read.
    Network,
    /// The SDK-owned total-call deadline expired. Dropping the caller's future
    /// remains native cancellation; an injected client's timeout is `Network`.
    Timeout,
}

impl DecisionsErrorKind {
    /// **Experimental.** The cross-SDK name of this kind (for example `invalidEndpoint`).
    pub fn as_str(self) -> &'static str {
        match self {
            Self::EndpointUnavailable => "endpointUnavailable",
            Self::InvalidEndpoint => "invalidEndpoint",
            Self::UnsupportedProvider => "unsupportedProvider",
            Self::UnsupportedTransport => "unsupportedTransport",
            Self::ModelMismatch => "modelMismatch",
            Self::CredentialConflict => "credentialConflict",
            Self::Http => "http",
            Self::InvalidResponse => "invalidResponse",
            Self::Network => "network",
            Self::Timeout => "timeout",
        }
    }
}

impl fmt::Display for DecisionsErrorKind {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(self.as_str())
    }
}

/// **Experimental.** Details of a failed Decisions call. Retained diagnostics
/// are bounded and redact known credentials and their supported encodings.
///
/// Obtain it from an [`Error`] with [`Error::decisions_error`].
#[derive(Debug)]
pub struct DecisionsError {
    kind: DecisionsErrorKind,
    message: String,
    http: Option<Box<HttpDetails>>,
    source: Option<BoxError>,
}

/// Response details of an HTTP error, boxed to keep `Result`s small.
#[derive(Debug)]
struct HttpDetails {
    status: u16,
    error_type: Option<String>,
    error_code: Option<String>,
    error_param: Option<String>,
    request_id: Option<String>,
    retry_after_ms: Option<u64>,
    body: String,
    body_truncated: bool,
}

impl DecisionsError {
    fn new(kind: DecisionsErrorKind, message: impl Into<String>) -> Self {
        Self {
            kind,
            message: message.into(),
            http: None,
            source: None,
        }
    }

    fn with_source(mut self, source: impl Into<BoxError>) -> Self {
        self.source = Some(source.into());
        self
    }

    /// **Experimental.** The error category.
    pub fn kind(&self) -> DecisionsErrorKind {
        self.kind
    }

    /// **Experimental.** The (redacted) error message.
    pub fn message(&self) -> &str {
        &self.message
    }

    /// **Experimental.** HTTP status for [`DecisionsErrorKind::Http`] errors.
    pub fn status(&self) -> Option<u16> {
        self.http.as_ref().map(|h| h.status)
    }

    /// **Experimental.** OpenAI error `type`, when the error body was parsable.
    pub fn error_type(&self) -> Option<&str> {
        self.http.as_ref()?.error_type.as_deref()
    }

    /// **Experimental.** OpenAI error `code`, when the error body was parsable.
    pub fn error_code(&self) -> Option<&str> {
        self.http.as_ref()?.error_code.as_deref()
    }

    /// **Experimental.** OpenAI error `param`, when the error body was parsable.
    pub fn error_param(&self) -> Option<&str> {
        self.http.as_ref()?.error_param.as_deref()
    }

    /// **Experimental.** Bounded, redacted `x-request-id` response header.
    pub fn request_id(&self) -> Option<&str> {
        self.http.as_ref()?.request_id.as_deref()
    }

    /// **Experimental.** Advisory provider retry delay in milliseconds for an
    /// HTTP error. Prefers valid `retry-after-ms` over `Retry-After`; never
    /// triggers automatic retries. Invalid or overflowing hints are absent.
    pub fn retry_after_ms(&self) -> Option<u64> {
        self.http.as_ref()?.retry_after_ms
    }

    /// **Experimental.** Response body (at most 64 KiB, credentials redacted).
    pub fn body(&self) -> Option<&str> {
        self.http.as_ref().map(|h| h.body.as_str())
    }

    /// **Experimental.** Whether the original body exceeded 64 KiB. Redaction
    /// expansion can reach the output cap without setting this flag.
    pub fn body_truncated(&self) -> Option<bool> {
        self.http.as_ref().map(|h| h.body_truncated)
    }
}

impl fmt::Display for DecisionsError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(&self.message)
    }
}

impl std::error::Error for DecisionsError {
    fn source(&self) -> Option<&(dyn std::error::Error + 'static)> {
        self.source
            .as_deref()
            .map(|e| e as &(dyn std::error::Error + 'static))
    }
}

impl From<DecisionsError> for Error {
    fn from(error: DecisionsError) -> Self {
        Error::new(ErrorKind::Decisions(error.kind), error)
    }
}

/// **Experimental.** Sanitized stand-in for a transport error, used as
/// the source of [`DecisionsErrorKind::Network`] errors. Only a category name,
/// an optional I/O error code, and the redacted message are retained, so
/// arbitrary exception objects, URL/header attributes, and source chains are
/// not retained. Only known credentials and supported encodings are redacted.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct DecisionsErrorCause {
    name: String,
    message: String,
    code: Option<String>,
}

impl DecisionsErrorCause {
    /// **Experimental.** Category of the original error (for example
    /// `ConnectError`, `TimeoutError`, `BodyError`, `IoError`, or `Error`).
    pub fn name(&self) -> &str {
        &self.name
    }

    /// **Experimental.** The redacted message of the original error chain.
    pub fn message(&self) -> &str {
        &self.message
    }

    /// **Experimental.** The [`std::io::ErrorKind`] name of an underlying I/O
    /// error (for example `ConnectionReset`), if any.
    pub fn code(&self) -> Option<&str> {
        self.code.as_deref()
    }
}

impl fmt::Display for DecisionsErrorCause {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(&self.message)
    }
}

impl std::error::Error for DecisionsErrorCause {}

fn decisions_error(kind: DecisionsErrorKind, message: impl Into<String>) -> DecisionsError {
    DecisionsError::new(kind, message)
}

fn invalid_endpoint(message: &str) -> DecisionsError {
    decisions_error(DecisionsErrorKind::InvalidEndpoint, message)
}

fn error_name(error: &(dyn std::error::Error + 'static)) -> &'static str {
    if let Some(e) = error.downcast_ref::<reqwest::Error>() {
        return if e.is_timeout() {
            "TimeoutError"
        } else if e.is_connect() {
            "ConnectError"
        } else if e.is_body() {
            "BodyError"
        } else if e.is_decode() {
            "DecodeError"
        } else if e.is_redirect() {
            "RedirectError"
        } else if e.is_builder() {
            "BuilderError"
        } else if e.is_request() {
            "RequestError"
        } else {
            "Error"
        };
    }
    if error.is::<std::io::Error>() {
        return "IoError";
    }
    "Error"
}

fn sanitize_cause(error: &BoxError, redactor: &Redactor) -> DecisionsErrorCause {
    let top: &(dyn std::error::Error + 'static) = &**error;
    let limit = MAX_ERROR_BODY_BYTES.saturating_add(redactor.max_secret_bytes);
    let mut message = truncate_utf8(top.to_string(), limit);
    let mut code = None;
    let mut current = Some(top);
    // A custom transport may supply a cyclic or arbitrarily deep error chain.
    for _ in 0..16 {
        let Some(e) = current else { break };
        if code.is_none()
            && let Some(io) = e.downcast_ref::<std::io::Error>()
        {
            code = Some(format!("{:?}", io.kind()));
        }
        if !std::ptr::addr_eq(e, top) && message.len() < limit {
            let text = e.to_string();
            if !text.is_empty() && !message.contains(&text) {
                message.push_str(": ");
                message.push_str(&text);
                message = truncate_utf8(message, limit);
            }
        }
        current = e.source();
    }
    DecisionsErrorCause {
        name: redactor.redact(error_name(top)),
        message: redactor.redact(&message),
        code: code.map(|code| redactor.redact(&code)),
    }
}

fn network_error(message: &str, error: &BoxError, redactor: &Redactor) -> DecisionsError {
    decisions_error(DecisionsErrorKind::Network, message)
        .with_source(sanitize_cause(error, redactor))
}

fn endpoint_unavailable(_cause: Error) -> DecisionsError {
    decisions_error(
        DecisionsErrorKind::EndpointUnavailable,
        format!(
            "Could not resolve the session's provider endpoint for the Decisions API. Ensure the runtime supports this API and {GATE_ENV}=true is set in the runtime's environment (or in the host environment before an in-process runtime is loaded)."
        ),
    )
}

// ============================================================================
// Endpoint -> request plan
// ============================================================================

fn is_http_whitespace(c: char) -> bool {
    c == ' ' || c == '\t'
}

fn trim_http_whitespace(value: &str) -> &str {
    value.trim_matches(is_http_whitespace)
}

fn is_token_byte(b: u8) -> bool {
    b.is_ascii_alphanumeric() || b"!#$%&'*+-.^_`|~".contains(&b)
}

/// Builds the Decisions URL from the endpoint's base URL.
fn build_url(
    endpoint_type: &ProviderEndpointType,
    base_url: &str,
) -> Result<String, DecisionsError> {
    let malformed = || invalid_endpoint("The provider endpoint base URL is not a valid HTTP URL.");
    if base_url
        .bytes()
        .any(|b| !b.is_ascii() || b <= b' ' || b == 0x7f || b == b'\\')
    {
        return Err(malformed());
    }
    let Some((scheme, rest)) = base_url.split_once("://") else {
        return Err(malformed());
    };
    if !scheme.eq_ignore_ascii_case("http") && !scheme.eq_ignore_ascii_case("https") {
        return Err(malformed());
    }
    let bytes = base_url.as_bytes();
    for (i, &b) in bytes.iter().enumerate() {
        if b == b'%'
            && !bytes
                .get(i + 1..i + 3)
                .is_some_and(|digits| digits.iter().all(u8::is_ascii_hexdigit))
        {
            return Err(malformed());
        }
    }
    let authority = rest.split(['/', '?', '#']).next().unwrap_or_default();
    if authority.is_empty() || authority.contains('@') {
        return Err(malformed());
    }
    let port = if authority.starts_with('[') {
        let Some((_, tail)) = authority.split_once(']') else {
            return Err(malformed());
        };
        if tail.is_empty() {
            None
        } else {
            Some(tail.strip_prefix(':').ok_or_else(malformed)?)
        }
    } else {
        authority.split_once(':').map(|(_, port)| port)
    };
    if let Some(port) = port
        && (port.is_empty()
            || !port.bytes().all(|b| b.is_ascii_digit())
            || port.parse::<u16>().is_err())
    {
        return Err(malformed());
    }
    let url = reqwest::Url::parse(base_url).map_err(|_| {
        invalid_endpoint("The provider endpoint base URL is not a valid absolute URL.")
    })?;
    let scheme = url.scheme();
    let host = url
        .host_str()
        .filter(|host| !host.is_empty())
        .ok_or_else(malformed)?;
    if !host.starts_with('[')
        && host
            .strip_suffix('.')
            .unwrap_or(host)
            .split('.')
            .any(|label| {
                label.is_empty()
                    || label.len() > 63
                    || label.starts_with('-')
                    || label.ends_with('-')
                    || !label
                        .bytes()
                        .all(|b| b.is_ascii_alphanumeric() || b == b'-' || b == b'_')
            })
    {
        return Err(malformed());
    }
    let port = url.port().map(|p| format!(":{p}")).unwrap_or_default();
    let base_path = url.path().trim_end_matches('/');
    let suffix = if *endpoint_type == ProviderEndpointType::Azure
        && !base_path.to_ascii_lowercase().ends_with("/openai/v1")
    {
        "/openai/v1/decisions"
    } else {
        "/decisions"
    };
    let search = match url.query() {
        Some(q) if !q.is_empty() => format!("?{q}"),
        _ => String::new(),
    };
    Ok(format!(
        "{scheme}://{host}{port}{base_path}{suffix}{search}"
    ))
}

fn check_header<'v>(name: &str, value: &'v str) -> Result<&'v str, DecisionsError> {
    let name_ok = !name.is_empty() && name.bytes().all(is_token_byte);
    // CR, LF, and NUL are rejected as in every SDK; other control characters
    // are rejected too because they cannot be sent as an HTTP header value.
    let value_ok = !value
        .chars()
        .any(|c| (c.is_ascii_control() && c != '\t') || c == '\u{7f}');
    if !name_ok || !value_ok {
        return Err(invalid_endpoint(
            "The provider endpoint returned an invalid HTTP header.",
        ));
    }
    Ok(trim_http_whitespace(value))
}

/// Merges endpoint headers and credentials into the outbound header set
/// (lowercase names).
fn build_headers(endpoint: &ProviderEndpoint) -> Result<IndexMap<String, String>, DecisionsError> {
    if let Some(api_key) = endpoint.api_key.as_deref() {
        check_header("api-key", api_key)?;
    }
    let mut merged: IndexMap<String, String> = IndexMap::new();
    for (name, raw_value) in &endpoint.headers {
        let value = check_header(name, raw_value)?;
        let lower = name.to_ascii_lowercase();
        if lower == "content-type" || lower == "accept" {
            continue;
        }
        if let Some(existing) = merged.get(&lower)
            && existing != value
        {
            return Err(invalid_endpoint(
                "The provider endpoint returned conflicting values for the same HTTP header.",
            ));
        }
        merged.insert(lower, value.to_owned());
    }

    let mut api_key_header: Option<&str> = None;
    if let Some(api_key) = endpoint.api_key.as_deref()
        && !api_key.is_empty()
        && !merged.contains_key("authorization")
        && !merged.contains_key("api-key")
    {
        if endpoint.r#type == ProviderEndpointType::Azure {
            api_key_header = Some("api-key");
            let value = check_header("api-key", api_key)?;
            merged.insert("api-key".to_owned(), value.to_owned());
        } else {
            api_key_header = Some("authorization");
            let value = check_header("authorization", api_key)?;
            merged.insert("authorization".to_owned(), format!("Bearer {value}"));
        }
    }

    if let Some(token) = &endpoint.session_token {
        let value = check_header(&token.header, &token.token)?;
        let lower = token.header.to_ascii_lowercase();
        if api_key_header == Some(lower.as_str()) {
            return Err(decisions_error(
                DecisionsErrorKind::CredentialConflict,
                "The provider session token uses the same header as the endpoint API key.",
            ));
        }
        merged.insert(lower, value.to_owned());
    }

    merged.insert("content-type".to_owned(), "application/json".to_owned());
    merged.insert("accept".to_owned(), "application/json".to_owned());
    Ok(merged)
}

/// The effective URL and credentials of an attempt. A 401/403 is retried only
/// when this changes.
#[derive(Debug, Clone, PartialEq, Eq)]
struct Fingerprint {
    url: String,
    authorization: Option<String>,
    api_key: Option<String>,
    session_token: Option<(String, Option<String>)>,
}

struct AttemptPlan {
    url: String,
    headers: IndexMap<String, String>,
    fingerprint: Fingerprint,
    redactor: Redactor,
}

/// Validates the endpoint and builds the request plan (deterministic check order).
fn plan_attempt(endpoint: &ProviderEndpoint, model: &str) -> Result<AttemptPlan, DecisionsError> {
    if !matches!(
        endpoint.r#type,
        ProviderEndpointType::Openai | ProviderEndpointType::Azure
    ) {
        return Err(decisions_error(
            DecisionsErrorKind::UnsupportedProvider,
            "The session's provider type does not support the Decisions API.",
        ));
    }
    if let Some(transport) = &endpoint.transport
        && *transport != ProviderEndpointTransport::Http
    {
        return Err(decisions_error(
            DecisionsErrorKind::UnsupportedTransport,
            "The session's provider transport is not supported by the Decisions API.",
        ));
    }
    if let Some(token_model) = endpoint
        .session_token
        .as_ref()
        .and_then(|t| t.model.as_deref())
        && token_model != model
    {
        return Err(decisions_error(
            DecisionsErrorKind::ModelMismatch,
            "The provider session token is bound to a different model. Set endpoint_model_id or request.model consistently.",
        ));
    }
    let url = build_url(&endpoint.r#type, &endpoint.base_url)?;
    let headers = build_headers(endpoint)?;
    let session_token = endpoint.session_token.as_ref().map(|t| {
        let lower = t.header.to_ascii_lowercase();
        let value = headers.get(&lower).cloned();
        (lower, value)
    });
    let fingerprint = Fingerprint {
        url: url.clone(),
        authorization: headers.get("authorization").cloned(),
        api_key: headers.get("api-key").cloned(),
        session_token,
    };
    let redactor = Redactor::new(collect_secrets(endpoint, &url));
    Ok(AttemptPlan {
        url,
        headers,
        fingerprint,
        redactor,
    })
}

// ============================================================================
// Redaction
// ============================================================================

/// Strips a leading `^[ \t]*bearer[ \t]+` (case-insensitive).
fn strip_bearer(value: &str) -> Option<&str> {
    let rest = value.trim_start_matches(is_http_whitespace);
    let scheme = rest.get(..6)?;
    if !scheme.eq_ignore_ascii_case("bearer") {
        return None;
    }
    let after = &rest[6..];
    let stripped = after.trim_start_matches(is_http_whitespace);
    (stripped.len() < after.len()).then_some(stripped)
}

/// Every nonempty actual credential is protected; only other header/query
/// values use the Unicode-scalar minimum. Expand SP/HTAB-trimmed candidates
/// into supported wire encodings.
fn collect_secrets(endpoint: &ProviderEndpoint, url: &str) -> Vec<String> {
    let mut raw: IndexMap<String, ()> = IndexMap::new();
    let mut add = |value: &str, credential: bool| {
        let trimmed = trim_http_whitespace(value);
        if !trimmed.is_empty() && (credential || trimmed.chars().count() >= MIN_SECRET_LENGTH) {
            raw.insert(trimmed.to_owned(), ());
        }
    };
    if let Some(api_key) = endpoint.api_key.as_deref() {
        add(api_key, true);
    }
    if let Some(token) = &endpoint.session_token {
        add(&token.token, true);
        if token.header.eq_ignore_ascii_case("authorization")
            && let Some(bearer) = strip_bearer(&token.token)
        {
            add(bearer, true);
        }
    }
    for (name, value) in &endpoint.headers {
        let credential = name.eq_ignore_ascii_case("authorization")
            || name.eq_ignore_ascii_case("api-key")
            || endpoint
                .session_token
                .as_ref()
                .is_some_and(|token| token.header.eq_ignore_ascii_case(name));
        add(value, credential);
        add(strip_bearer(value).unwrap_or(value), credential);
    }
    if let Ok(parsed) = reqwest::Url::parse(url) {
        let raw_pairs = parsed
            .query()
            .unwrap_or("")
            .split('&')
            .filter(|pair| !pair.is_empty());
        for (raw_pair, (_, value)) in raw_pairs.zip(parsed.query_pairs()) {
            add(&value, false);
            if trim_http_whitespace(&value).chars().count() >= MIN_SECRET_LENGTH {
                let raw_value = raw_pair.split_once('=').map_or("", |(_, value)| value);
                add(raw_value, false);
            }
        }
    }
    let mut variants: IndexMap<String, ()> = IndexMap::new();
    for secret in raw.keys() {
        for variant in secret_variants(secret) {
            variants.insert(variant, ());
        }
    }
    let mut out: Vec<String> = variants.into_keys().collect();
    out.sort_by_key(|v| std::cmp::Reverse(v.len()));
    out
}

/// `JSON.stringify(value)` without the surrounding quotes.
fn json_escape(value: &str) -> String {
    let quoted = serde_json::to_string(value).unwrap_or_default();
    quoted[1..quoted.len() - 1].to_owned()
}

/// Escapes every UTF-16 code unit at or above U+007F as `\uxxxx`.
fn ascii_json_escape(json_escaped: &str) -> String {
    let mut out = String::with_capacity(json_escaped.len());
    for c in json_escaped.chars() {
        if (c as u32) < 0x7f {
            out.push(c);
        } else {
            let mut units = [0u16; 2];
            for unit in c.encode_utf16(&mut units) {
                out.push_str(&format!("\\u{unit:04x}"));
            }
        }
    }
    out
}

/// JavaScript `encodeURIComponent`.
fn encode_uri_component(value: &str) -> String {
    let mut out = String::with_capacity(value.len());
    for b in value.bytes() {
        if b.is_ascii_alphanumeric() || b"-_.!~*'()".contains(&b) {
            out.push(b as char);
        } else {
            out.push_str(&format!("%{b:02X}"));
        }
    }
    out
}

/// Wire encodings under which a credential may be echoed back in a response body.
fn secret_variants(secret: &str) -> Vec<String> {
    let json_escaped = json_escape(secret);
    let ascii_escaped = ascii_json_escape(&json_escaped);
    let uri_encoded = encode_uri_component(secret);
    vec![
        secret.to_owned(),
        json_escaped.clone(),
        ascii_escaped.clone(),
        json_escaped.replace('/', "\\/"),
        ascii_escaped.replace('/', "\\/"),
        uri_encoded.clone(),
        uri_encoded.replace("%20", "+"),
    ]
}

/// Regex source for one secret variant. Hex digits inside `\uXXXX` and `%XX`
/// escapes match case-insensitively because both casings are valid encodings
/// of the same character.
fn variant_pattern(variant: &str) -> String {
    fn hex(out: &mut String, c: char) {
        if c.is_ascii_alphabetic() {
            out.push('[');
            out.push(c.to_ascii_lowercase());
            out.push(c.to_ascii_uppercase());
            out.push(']');
        } else {
            out.push(c);
        }
    }
    let chars: Vec<char> = variant.chars().collect();
    let is_hex = |range: &[char]| range.iter().all(char::is_ascii_hexdigit);
    let mut out = String::new();
    let mut i = 0;
    while i < chars.len() {
        if chars[i] == '\\'
            && chars.get(i + 1) == Some(&'u')
            && i + 6 <= chars.len()
            && is_hex(&chars[i + 2..i + 6])
        {
            out.push_str("\\\\u");
            for &c in &chars[i + 2..i + 6] {
                hex(&mut out, c);
            }
            i += 6;
        } else if chars[i] == '%' && i + 3 <= chars.len() && is_hex(&chars[i + 1..i + 3]) {
            out.push('%');
            for &c in &chars[i + 1..i + 3] {
                hex(&mut out, c);
            }
            i += 3;
        } else {
            out.push_str(&regex::escape(chars[i].encode_utf8(&mut [0; 4])));
            i += 1;
        }
    }
    out
}

struct Redactor {
    patterns: Vec<(String, Option<regex::Regex>)>,
    max_secret_bytes: usize,
}

impl Redactor {
    fn new(secrets: Vec<String>) -> Self {
        let max_secret_bytes = secrets.iter().map(String::len).max().unwrap_or(0);
        let patterns = secrets
            .into_iter()
            .filter(|secret| !secret.is_empty())
            .map(|secret| {
                let regex = regex::RegexBuilder::new(&variant_pattern(&secret))
                    .size_limit(1 << 26)
                    .build()
                    .ok();
                (secret, regex)
            })
            .collect();
        Self {
            patterns,
            max_secret_bytes,
        }
    }

    fn include(&mut self, previous: Self) {
        self.max_secret_bytes = self.max_secret_bytes.max(previous.max_secret_bytes);
        self.patterns.extend(previous.patterns);
    }

    /// Protects the original prefix, using lookahead only to recognize secrets
    /// crossing its boundary. Replacement never pulls lookahead into output.
    fn redact(&self, text: &str) -> String {
        self.redact_prefix(text, char_boundary(text, MAX_ERROR_BODY_BYTES))
    }

    fn redact_prefix(&self, text: &str, retained_end: usize) -> String {
        let search_end = char_boundary(text, retained_end.saturating_add(self.max_secret_bytes));
        let searched = &text[..search_end];
        let mut intervals = Vec::new();
        for (secret, regex) in &self.patterns {
            if let Some(regex) = regex {
                let mut from = 0;
                while let Some(found) = regex.find_at(searched, from) {
                    intervals.push((found.start(), found.end()));
                    // find_iter skips self-overlapping occurrences.
                    from = found.start()
                        + searched[found.start()..].chars().next().unwrap().len_utf8();
                }
            } else {
                // Large patterns may exceed the regex compiler's size limit.
                // Preserve escape-case semantics even in that fallback.
                for (start, _) in searched.char_indices() {
                    let end = start + secret.len();
                    if let Some(candidate) = searched.as_bytes().get(start..end)
                        && variant_matches(secret.as_bytes(), candidate)
                    {
                        intervals.push((start, end));
                    }
                }
            }
        }
        intervals.sort_unstable();
        let mut unions: Vec<(usize, usize)> = Vec::new();
        for (start, end) in intervals {
            if let Some(last) = unions.last_mut()
                && start < last.1
            {
                last.1 = last.1.max(end);
            } else {
                unions.push((start, end));
            }
        }
        let mut result = String::new();
        let mut cursor = 0;
        for (start, end) in unions {
            if start >= retained_end {
                break;
            }
            result.push_str(&text[cursor..start]);
            result.push_str(REDACTED);
            cursor = end;
        }
        if cursor < retained_end {
            result.push_str(&text[cursor..retained_end]);
        }
        truncate_utf8(result, MAX_ERROR_BODY_BYTES)
    }
}

fn variant_matches(pattern: &[u8], candidate: &[u8]) -> bool {
    let mut i = 0;
    while i < pattern.len() {
        let hex_len = if pattern[i..].starts_with(b"\\u") {
            4
        } else if pattern[i] == b'%' {
            2
        } else {
            0
        };
        let prefix_len = if hex_len == 4 { 2 } else { 1 };
        let end = i + prefix_len + hex_len;
        if hex_len > 0
            && pattern
                .get(i + prefix_len..end)
                .is_some_and(|hex| hex.iter().all(u8::is_ascii_hexdigit))
        {
            if pattern[i..i + prefix_len] != candidate[i..i + prefix_len]
                || !pattern[i + prefix_len..end]
                    .eq_ignore_ascii_case(&candidate[i + prefix_len..end])
            {
                return false;
            }
            i = end;
        } else {
            if pattern[i] != candidate[i] {
                return false;
            }
            i += 1;
        }
    }
    true
}

// ============================================================================
// Response parsing
// ============================================================================

struct InvalidShape(String);

type Shape<T> = Result<T, InvalidShape>;

fn shape_err<T>(message: impl Into<String>) -> Shape<T> {
    Err(InvalidShape(message.into()))
}

fn num(obj: &Map<String, Value>, key: &str) -> Shape<f64> {
    match obj.get(key).and_then(Value::as_f64) {
        Some(n) if n.is_finite() => Ok(n),
        _ => shape_err(format!("Expected number '{key}'.")),
    }
}

fn integer(value: Option<&Value>, key: &str) -> Shape<i64> {
    match value {
        Some(Value::Number(n)) => {
            if let Some(i) = n.as_i64() {
                return Ok(i);
            }
            let token = n.to_string();
            if token.contains(['.', 'e', 'E'])
                && let Some(integer) = decimal_integer(&token)
            {
                return Ok(integer);
            }
            shape_err(format!("Expected signed integer '{key}'."))
        }
        _ => shape_err(format!("Expected signed integer '{key}'.")),
    }
}

/// Only mathematically integral decimal/exponent encodings inside the portable
/// safe range are accepted. Inspect the original token, never a rounded f64.
fn decimal_integer(token: &str) -> Option<i64> {
    let negative = token.starts_with('-');
    let unsigned = token.strip_prefix('-').unwrap_or(token);
    let (mantissa, exponent) = match unsigned.find(['e', 'E']) {
        Some(index) => (&unsigned[..index], Some(&unsigned[index + 1..])),
        None => (unsigned, None),
    };
    let digits: Vec<u8> = mantissa.bytes().filter(|b| *b != b'.').collect();
    let Some(first) = digits.iter().position(|b| *b != b'0') else {
        return Some(0);
    };
    let mut digits = &digits[first..];
    let fractional = mantissa
        .split_once('.')
        .map_or(0, |(_, fraction)| fraction.len());
    let exponent = match exponent {
        Some(exponent) => exponent.parse::<i64>().ok()?,
        None => 0,
    };
    let scale = exponent.checked_sub(i64::try_from(fractional).ok()?)?;
    let zeros = if scale < 0 {
        let remove = usize::try_from(scale.unsigned_abs()).ok()?;
        let end = digits.len().checked_sub(remove)?;
        if !digits[end..].iter().all(|b| *b == b'0') {
            return None;
        }
        digits = &digits[..end];
        0
    } else {
        usize::try_from(scale).ok()?
    };
    if digits.len().checked_add(zeros)? > 16 {
        return None;
    }
    let mut magnitude = 0i64;
    for digit in digits {
        magnitude = magnitude
            .checked_mul(10)?
            .checked_add(i64::from(digit - b'0'))?;
    }
    magnitude = magnitude.checked_mul(10i64.checked_pow(u32::try_from(zeros).ok()?)?)?;
    if magnitude > MAX_SAFE_INTEGER {
        return None;
    }
    Some(if negative { -magnitude } else { magnitude })
}

fn token_count(obj: Option<&Map<String, Value>>, key: &str) -> Shape<i64> {
    let value = obj.and_then(|obj| obj.get(key));
    if value.is_none() {
        Ok(0)
    } else {
        integer(value, key)
    }
}

fn probability(obj: &Map<String, Value>, key: &str) -> Shape<f64> {
    let value = num(obj, key)?;
    if (0.0..=1.0).contains(&value) {
        Ok(value)
    } else {
        shape_err(format!("Expected probability '{key}' in [0, 1]."))
    }
}

fn optional_object<'a>(
    obj: &'a Map<String, Value>,
    key: &str,
) -> Shape<Option<&'a Map<String, Value>>> {
    match obj.get(key) {
        None => Ok(None),
        Some(Value::Object(value)) => Ok(Some(value)),
        _ => shape_err(format!("Expected object '{key}'.")),
    }
}

fn string(obj: &Map<String, Value>, key: &str) -> Shape<String> {
    match obj.get(key) {
        Some(Value::String(s)) => Ok(s.clone()),
        _ => shape_err(format!("Expected string '{key}'.")),
    }
}

fn objects<'a>(obj: &'a Map<String, Value>, key: &str) -> Shape<Vec<&'a Map<String, Value>>> {
    let err = || InvalidShape(format!("Expected array of objects '{key}'."));
    let Some(Value::Array(items)) = obj.get(key) else {
        return Err(err());
    };
    items
        .iter()
        .map(|item| item.as_object().ok_or_else(err))
        .collect()
}

fn choice_value(obj: &Map<String, Value>, key: &str) -> Shape<DecisionsChoiceValue> {
    match obj.get(key) {
        Some(Value::String(s)) => Ok(DecisionsChoiceValue::String(s.clone())),
        Some(Value::Bool(b)) => Ok(DecisionsChoiceValue::Bool(*b)),
        _ => shape_err(format!("Expected string or boolean '{key}'.")),
    }
}

fn answer_name(obj: &Map<String, Value>) -> Shape<Option<String>> {
    match obj.get("name") {
        None | Some(Value::Null) => Ok(None),
        Some(Value::String(s)) => Ok(Some(s.clone())),
        Some(_) => shape_err("Expected string 'name'."),
    }
}

fn parse_answer(obj: &Map<String, Value>) -> Shape<DecisionsAnswer> {
    let wire_type = string(obj, "type")?;
    let name = answer_name(obj)?;
    Ok(match wire_type.as_str() {
        "predicate" => DecisionsAnswer::Predicate(DecisionsPredicateAnswer {
            name,
            probability: probability(obj, "probability")?,
        }),
        "choice" => DecisionsAnswer::Choice(DecisionsChoiceAnswer {
            name,
            choice: choice_value(obj, "choice")?,
            confidence: probability(obj, "confidence")?,
            probabilities: objects(obj, "probabilities")?
                .into_iter()
                .map(|p| {
                    Ok(DecisionsChoiceProbability {
                        value: choice_value(p, "value")?,
                        probability: probability(p, "probability")?,
                    })
                })
                .collect::<Shape<_>>()?,
        }),
        "score" => DecisionsAnswer::Score(DecisionsScoreAnswer {
            name,
            score: num(obj, "score")?,
            confidence: probability(obj, "confidence")?,
            probabilities: objects(obj, "probabilities")?
                .into_iter()
                .map(|p| {
                    Ok(DecisionsScoreProbability {
                        label: string(p, "label")?,
                        value: integer(p.get("value"), "value")?,
                        probability: probability(p, "probability")?,
                    })
                })
                .collect::<Shape<_>>()?,
        }),
        "refusal" => DecisionsAnswer::Refusal(DecisionsRefusalAnswer { name }),
        _ => DecisionsAnswer::Unknown {
            name,
            wire_type,
            raw: Value::Object(obj.clone()),
        },
    })
}

fn parse_result_shape(json: &Value) -> Shape<DecisionsResult> {
    let Some(root) = json.as_object() else {
        return shape_err("Expected a JSON object.");
    };
    let Some(usage) = root.get("usage").and_then(Value::as_object) else {
        return shape_err("Expected object 'usage'.");
    };
    let input_details = optional_object(usage, "input_tokens_details")?;
    let output_details = optional_object(usage, "output_tokens_details")?;
    let answers = objects(root, "answers")?
        .into_iter()
        .map(parse_answer)
        .collect::<Shape<_>>()?;
    let model = string(root, "model")?;
    Ok(DecisionsResult {
        answers,
        model,
        usage: DecisionsUsage {
            input_tokens: token_count(Some(usage), "input_tokens")?,
            input_tokens_details: DecisionsInputTokensDetails {
                cached_tokens: token_count(input_details, "cached_tokens")?,
                cache_write_tokens: token_count(input_details, "cache_write_tokens")?,
            },
            output_tokens: token_count(Some(usage), "output_tokens")?,
            output_tokens_details: DecisionsOutputTokensDetails {
                reasoning_tokens: token_count(output_details, "reasoning_tokens")?,
            },
            total_tokens: token_count(Some(usage), "total_tokens")?,
        },
    })
}

/// Parses a wire response into a typed result.
fn parse_result(json: &Value) -> Result<DecisionsResult, DecisionsError> {
    parse_result_shape(json).map_err(|InvalidShape(message)| {
        decisions_error(
            DecisionsErrorKind::InvalidResponse,
            format!("The Decisions API returned an unexpected response: {message}"),
        )
    })
}

// ============================================================================
// HTTP
// ============================================================================

type BodyStream = BoxStream<'static, Result<Bytes, BoxError>>;

/// A response whose body has not been read yet.
pub(crate) struct InboundResponse {
    pub(crate) status: u16,
    pub(crate) request_id: Option<String>,
    pub(crate) retry_after_ms: Option<u64>,
    pub(crate) body: BodyStream,
}

/// Sends one POST request. Implemented over reqwest; replaced in tests.
pub(crate) trait Transport: Send + Sync {
    fn send<'a>(
        &'a self,
        url: &'a str,
        headers: &'a IndexMap<String, String>,
        body: Bytes,
    ) -> BoxFuture<'a, Result<InboundResponse, BoxError>>;
}

struct ReqwestTransport {
    client: Option<reqwest::Client>,
}

impl ReqwestTransport {
    fn new(client: Option<reqwest::Client>) -> Self {
        Self { client }
    }
}

impl Transport for ReqwestTransport {
    fn send<'a>(
        &'a self,
        url: &'a str,
        headers: &'a IndexMap<String, String>,
        body: Bytes,
    ) -> BoxFuture<'a, Result<InboundResponse, BoxError>> {
        Box::pin(async move {
            let client = match &self.client {
                Some(client) => client,
                None => crate::copilot_request_handler::SHARED_HTTP_CLIENT
                    .as_ref()
                    .map_err(|_| BoxError::from("HTTP TLS configuration failed."))?,
            };
            let mut map = reqwest::header::HeaderMap::with_capacity(headers.len());
            for (name, value) in headers {
                let mut value = reqwest::header::HeaderValue::from_bytes(value.as_bytes())?;
                value.set_sensitive(true);
                map.insert(
                    reqwest::header::HeaderName::from_bytes(name.as_bytes())?,
                    value,
                );
            }
            let response = client
                .post(url)
                .headers(map)
                .body(body)
                .send()
                .await
                .map_err(|e| BoxError::from(e.without_url()))?;
            let status = response.status().as_u16();
            let request_id = response
                .headers()
                .get("x-request-id")
                .map(|v| String::from_utf8_lossy(v.as_bytes()).into_owned());
            let header = |name| {
                response
                    .headers()
                    .get(name)
                    .and_then(|value| value.to_str().ok())
            };
            let retry_after_ms = retry_after::parse(
                header("retry-after-ms"),
                header("retry-after"),
                retry_after::now_epoch_ms(),
            );
            let body = response
                .bytes_stream()
                .map(|chunk| chunk.map_err(|e| BoxError::from(e.without_url())))
                .boxed();
            Ok(InboundResponse {
                status,
                request_id,
                retry_after_ms,
                body,
            })
        })
    }
}

/// Reads at most `limit` bytes. `exceeded` reports whether the stream held
/// more data; the caller then drops the stream to release the connection.
async fn read_limited(body: &mut BodyStream, limit: usize) -> Result<(Vec<u8>, bool), BoxError> {
    let mut bytes = Vec::new();
    while let Some(chunk) = body.next().await {
        tokio::task::consume_budget().await;
        let chunk = chunk?;
        let remaining = limit - bytes.len();
        if chunk.len() > remaining {
            bytes.extend_from_slice(&chunk[..remaining]);
            return Ok((bytes, true));
        }
        bytes.extend_from_slice(&chunk);
    }
    Ok((bytes, false))
}

/// Lossy UTF-8 decoding that drops a leading BOM, like `TextDecoder`.
fn decode_utf8(bytes: &[u8]) -> String {
    let bytes = bytes.strip_prefix(b"\xEF\xBB\xBF").unwrap_or(bytes);
    String::from_utf8_lossy(bytes).into_owned()
}

/// Truncates to at most `max` bytes, backing off to a char boundary so a split
/// trailing character is dropped entirely (never replaced with U+FFFD).
fn truncate_utf8(mut text: String, max: usize) -> String {
    if text.len() > max {
        text.truncate(char_boundary(&text, max));
    }
    text
}

fn char_boundary(text: &str, max: usize) -> usize {
    let mut end = max.min(text.len());
    while !text.is_char_boundary(end) {
        end -= 1;
    }
    end
}

/// Maps the original byte budget through BOM removal and lossy decoding.
/// Invalid bytes may expand, but must not shift the original retention limit.
fn decode_error_body(bytes: &[u8], exceeded: bool) -> (String, usize) {
    let raw = bytes.strip_prefix(b"\xEF\xBB\xBF").unwrap_or(bytes);
    let budget = MAX_ERROR_BODY_BYTES.saturating_sub(bytes.len() - raw.len());
    let mut text = String::new();
    let mut offset = 0;
    let mut retained_end = 0;
    for chunk in raw.utf8_chunks() {
        let valid = chunk.valid();
        if offset < budget {
            retained_end = text.len() + char_boundary(valid, budget - offset);
        }
        text.push_str(valid);
        offset += valid.len();
        let invalid = chunk.invalid();
        if !invalid.is_empty() {
            let incomplete = exceeded
                && offset + invalid.len() == raw.len()
                && std::str::from_utf8(invalid).is_err_and(|error| error.error_len().is_none());
            if !incomplete {
                text.push('\u{fffd}');
                if offset + invalid.len() <= budget {
                    retained_end = text.len();
                }
            }
            offset += invalid.len();
        }
    }
    (text, retained_end)
}

/// JavaScript `String(number)` for the common cases.
fn js_number_string(n: &serde_json::Number) -> String {
    if n.is_i64() || n.is_u64() {
        return n.to_string();
    }
    match n.as_f64() {
        Some(f) if f.fract() == 0.0 && f.abs() < 1e21 => format!("{}", f as i128),
        Some(f) => format!("{f}"),
        None => n.to_string(),
    }
}

fn string_field(value: Option<&Value>) -> Option<String> {
    match value? {
        Value::String(s) => Some(s.clone()),
        Value::Number(n) => Some(js_number_string(n)),
        Value::Bool(b) => Some(b.to_string()),
        _ => None,
    }
}

/// Maps a non-2xx response to a [`DecisionsError`] (bounded, redacted). A
/// lookahead window recognizes credentials crossing the ORIGINAL byte cap;
/// no other content from that window is retained. `Err` means the body could
/// not be read, so authentication refresh/replay must not occur.
async fn http_error(
    mut response: InboundResponse,
    redactor: &Redactor,
) -> Result<DecisionsError, BoxError> {
    let window = MAX_ERROR_BODY_BYTES.saturating_add(redactor.max_secret_bytes);
    let (bytes, exceeded) = read_limited(&mut response.body, window).await?;
    drop(response.body);
    let (raw, retained_end) = decode_error_body(&bytes, exceeded);
    let truncated = exceeded || bytes.len() > MAX_ERROR_BODY_BYTES;
    let body = redactor.redact_prefix(&raw, retained_end);

    let mut error_type = None;
    let mut error_code = None;
    let mut error_param = None;
    let mut error_message = None;
    if !truncated
        && let Ok(Value::Object(parsed)) = serde_json::from_str::<Value>(&raw)
        && let Some(Value::Object(error)) = parsed.get("error")
    {
        let redact = |key: &str| string_field(error.get(key)).map(|s| redactor.redact(&s));
        error_type = redact("type");
        error_code = redact("code");
        error_param = redact("param");
        error_message = string_field(error.get("message"));
    }

    let status = response.status;
    let status_text = if (300..400).contains(&status) {
        format!("HTTP {status} (redirects are not followed)")
    } else {
        format!("HTTP {status}")
    };
    let message = match error_message.as_deref() {
        Some(m) if !m.is_empty() => format!("Decisions request failed with {status_text}: {m}"),
        _ => format!("Decisions request failed with {status_text}."),
    };
    let mut error = decisions_error(DecisionsErrorKind::Http, redactor.redact(&message));
    error.http = Some(Box::new(HttpDetails {
        status,
        error_type,
        error_code,
        error_param,
        request_id: response.request_id.map(|id| redactor.redact(&id)),
        retry_after_ms: response.retry_after_ms,
        body,
        body_truncated: truncated,
    }));
    Ok(error)
}

/// `Ok` is the HTTP error; `Err` is a network error reading its body.
async fn read_http_error(
    response: InboundResponse,
    plan: &AttemptPlan,
) -> Result<DecisionsError, DecisionsError> {
    http_error(response, &plan.redactor).await.map_err(|error| {
        network_error(
            "The Decisions error response could not be read.",
            &error,
            &plan.redactor,
        )
    })
}

async fn send(
    transport: &dyn Transport,
    plan: &AttemptPlan,
    body: Bytes,
) -> Result<InboundResponse, DecisionsError> {
    transport
        .send(&plan.url, &plan.headers, body)
        .await
        .map_err(|error| {
            network_error(
                "The Decisions request could not be sent.",
                &error,
                &plan.redactor,
            )
        })
}

async fn resolve_endpoint<R, Fut>(
    resolve: &R,
    endpoint_model_id: Option<&str>,
) -> Result<ProviderEndpoint, DecisionsError>
where
    R: Fn(Option<String>) -> Fut,
    Fut: Future<Output = Result<ProviderEndpoint, Error>>,
{
    resolve(endpoint_model_id.map(str::to_owned))
        .await
        .map_err(endpoint_unavailable)
}

/// Implementation of `session.decisions().create`, independent of the session
/// so tests can inject the endpoint resolver and the transport.
pub(crate) async fn execute<R, Fut>(
    resolve: R,
    transport: &dyn Transport,
    request: &DecisionsRequest,
    endpoint_model_id: Option<&str>,
    timeout: Option<Duration>,
) -> Result<DecisionsResult, Error>
where
    R: Fn(Option<String>) -> Fut,
    Fut: Future<Output = Result<ProviderEndpoint, Error>>,
{
    if request.model.is_empty() {
        return Err(Error::with_message(
            ErrorKind::InvalidConfig,
            "DecisionsRequest.model is required.",
        ));
    }
    let deadline = timeout
        .map(|timeout| {
            tokio::time::Instant::now()
                .checked_add(timeout)
                .ok_or_else(|| {
                    Error::with_message(
                        ErrorKind::InvalidConfig,
                        "The Decisions timeout exceeds the supported duration.",
                    )
                })
        })
        .transpose()?;
    check_deadline(deadline)?;
    let operation = execute_inner(resolve, transport, request, endpoint_model_id, deadline);
    let result = match deadline {
        Some(deadline) => {
            tokio::select! {
                biased;
                _ = tokio::time::sleep_until(deadline) => Err(timeout_error()),
                result = operation => result,
            }
        }
        None => operation.await,
    };
    check_deadline(deadline)?;
    result
}

fn timeout_error() -> Error {
    decisions_error(
        DecisionsErrorKind::Timeout,
        "The Decisions API call exceeded its total-call deadline.",
    )
    .into()
}

fn check_deadline(deadline: Option<tokio::time::Instant>) -> Result<(), Error> {
    if deadline.is_some_and(|deadline| tokio::time::Instant::now() >= deadline) {
        Err(timeout_error())
    } else {
        Ok(())
    }
}

async fn execute_inner<R, Fut>(
    resolve: R,
    transport: &dyn Transport,
    request: &DecisionsRequest,
    endpoint_model_id: Option<&str>,
    deadline: Option<tokio::time::Instant>,
) -> Result<DecisionsResult, Error>
where
    R: Fn(Option<String>) -> Fut,
    Fut: Future<Output = Result<ProviderEndpoint, Error>>,
{
    check_deadline(deadline)?;
    let endpoint = resolve_endpoint(&resolve, endpoint_model_id).await?;
    check_deadline(deadline)?;
    let mut plan = plan_attempt(&endpoint, &request.model)?;
    let body = Bytes::from(serde_json::to_vec(request)?);
    check_deadline(deadline)?;
    let mut response = send(transport, &plan, body.clone()).await?;

    if response.status == 401 || response.status == 403 {
        let original = read_http_error(response, &plan).await?;
        check_deadline(deadline)?;
        let refreshed_endpoint = resolve_endpoint(&resolve, endpoint_model_id).await?;
        check_deadline(deadline)?;
        let Ok(mut refreshed) = plan_attempt(&refreshed_endpoint, &request.model) else {
            return Err(original.into());
        };
        if refreshed.fingerprint == plan.fingerprint {
            return Err(original.into());
        }
        refreshed.redactor.include(plan.redactor);
        plan = refreshed;
        check_deadline(deadline)?;
        response = send(transport, &plan, body).await?;
    }

    if !(200..300).contains(&response.status) {
        let (Ok(error) | Err(error)) = read_http_error(response, &plan).await;
        return Err(error.into());
    }

    let (bytes, exceeded) = read_limited(&mut response.body, MAX_RESPONSE_BYTES)
        .await
        .map_err(|error| {
            network_error(
                "The Decisions response could not be read.",
                &error,
                &plan.redactor,
            )
        })?;
    drop(response);
    if exceeded {
        return Err(decisions_error(
            DecisionsErrorKind::InvalidResponse,
            format!("The Decisions API response exceeded {MAX_RESPONSE_BYTES} bytes."),
        )
        .into());
    }
    let json: Value = serde_json::from_str(&decode_utf8(&bytes)).map_err(|_| {
        decisions_error(
            DecisionsErrorKind::InvalidResponse,
            "The Decisions API returned a response that is not valid JSON.",
        )
    })?;
    Ok(parse_result(&json)?)
}

// ============================================================================
// Session API
// ============================================================================

/// **Experimental.** The `session.decisions` API group. Obtain it with
/// [`Session::decisions`](crate::session::Session::decisions).
///
/// See the [module documentation](self) for requirements, retry, redirect,
/// redaction, and cancellation behavior.
#[derive(Clone, Copy)]
pub struct SessionDecisions<'a> {
    pub(crate) session: &'a Session,
}

impl fmt::Debug for SessionDecisions<'_> {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("SessionDecisions")
            .field("session_id", self.session.id())
            .finish()
    }
}

impl SessionDecisions<'_> {
    /// **Experimental.** Calls the Decisions API (`POST {baseUrl}/decisions`)
    /// against the session's provider. Requires the runtime to run with
    /// `COPILOT_ALLOW_GET_PROVIDER_ENDPOINT=true`.
    ///
    /// The 600-second deadline covers endpoint resolution, refresh, HTTP
    /// attempts, and bodies. Cancel natively by dropping the returned future.
    pub async fn create(&self, request: DecisionsRequest) -> Result<DecisionsResult, Error> {
        self.create_with_options(request, DecisionsOptions::default())
            .await
    }

    /// **Experimental.** Like [`create`](Self::create), with per-call options.
    ///
    /// Uses the same 600-second total-call deadline as [`create`](Self::create).
    /// Cancel natively by dropping the returned future.
    pub async fn create_with_options(
        &self,
        request: DecisionsRequest,
        options: DecisionsOptions,
    ) -> Result<DecisionsResult, Error> {
        self.create_with_timeout(request, options, Some(DEFAULT_TIMEOUT))
            .await
    }

    /// **Experimental.** Like [`create_with_options`](Self::create_with_options),
    /// with an explicit total-call deadline covering endpoint resolution,
    /// refresh, both HTTP attempts, and all body reads.
    ///
    /// `None` disables the SDK deadline; `Some(Duration::ZERO)` expires before
    /// I/O. Expiry returns [`DecisionsErrorKind::Timeout`] without retaining a
    /// provider cause. Missing model validation takes precedence.
    ///
    /// Dropping this future cancels in-flight HTTP work and drops response
    /// bodies. The runtime-side endpoint RPC is not cancelled, but a late reply
    /// cannot dispatch HTTP. No background task is spawned by this call.
    pub async fn create_with_timeout(
        &self,
        request: DecisionsRequest,
        options: DecisionsOptions,
        timeout: Option<Duration>,
    ) -> Result<DecisionsResult, Error> {
        let session = self.session;
        let transport = ReqwestTransport::new(options.http_client);
        let resolve = move |model_id: Option<String>| async move {
            let provider = session.rpc().provider();
            match model_id {
                Some(model_id) => {
                    provider
                        .get_endpoint_with_params(ProviderGetEndpointRequest {
                            model_id: Some(model_id),
                        })
                        .await
                }
                None => provider.get_endpoint().await,
            }
        };
        Box::pin(execute(
            resolve,
            &transport,
            &request,
            options.endpoint_model_id.as_deref(),
            timeout,
        ))
        .await
    }
}
