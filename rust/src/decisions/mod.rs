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
//! - Redirects are never followed. A 3xx response is reported as a
//!   [`DecisionsErrorKind::Http`](crate::decisions::DecisionsErrorKind::Http) error.
//! - Requests are sent directly to the provider; they are not routed through a
//!   [`CopilotRequestHandler`](crate::copilot_request_handler::CopilotRequestHandler).
//! - Error messages, bodies, and error sources never contain the endpoint's
//!   credentials: every known secret is redacted before it is surfaced.
//! - Error bodies are capped at 64 KiB and success bodies at 8 MiB.
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

mod tests;

type BoxError = Box<dyn std::error::Error + Send + Sync>;

const GATE_ENV: &str = "COPILOT_ALLOW_GET_PROVIDER_ENDPOINT";
const MAX_ERROR_BODY_BYTES: usize = 64 * 1024;
const MAX_RESPONSE_BYTES: usize = 8 * 1024 * 1024;
const MIN_SECRET_LENGTH: usize = 8;
const REDACTED: &str = "[REDACTED]";

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
    /// `input_json`
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
    /// and must provide their own redirect protection: build them with
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
    pub cached_tokens: u64,
    /// Tokens written to the prompt cache.
    pub cache_write_tokens: u64,
}

/// **Experimental.** Output token details.
#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
pub struct DecisionsOutputTokensDetails {
    /// Reasoning tokens.
    pub reasoning_tokens: u64,
}

/// **Experimental.** Token usage. Missing counts are reported as `0`.
#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
pub struct DecisionsUsage {
    /// Input tokens.
    pub input_tokens: u64,
    /// Input token details.
    pub input_tokens_details: DecisionsInputTokensDetails,
    /// Output tokens.
    pub output_tokens: u64,
    /// Output token details.
    pub output_tokens_details: DecisionsOutputTokensDetails,
    /// Total tokens.
    pub total_tokens: u64,
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
        }
    }
}

impl fmt::Display for DecisionsErrorKind {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(self.as_str())
    }
}

/// **Experimental.** Details of a failed Decisions call. Messages, bodies, and
/// sources never contain credentials.
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

    /// **Experimental.** Value of the `x-request-id` response header.
    pub fn request_id(&self) -> Option<&str> {
        self.http.as_ref()?.request_id.as_deref()
    }

    /// **Experimental.** Response body (at most 64 KiB, credentials redacted).
    pub fn body(&self) -> Option<&str> {
        self.http.as_ref().map(|h| h.body.as_str())
    }

    /// **Experimental.** Whether [`body`](Self::body) was truncated at 64 KiB.
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

/// **Experimental.** Credential-free stand-in for a transport error, used as
/// the source of [`DecisionsErrorKind::Network`] errors. Only a category name,
/// an optional I/O error code, and the redacted message are retained, so
/// request URLs and headers cannot leak.
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
    let mut message = top.to_string();
    let mut code = None;
    let mut current = Some(top);
    while let Some(e) = current {
        if code.is_none()
            && let Some(io) = e.downcast_ref::<std::io::Error>()
        {
            code = Some(format!("{:?}", io.kind()));
        }
        if !std::ptr::addr_eq(e, top) {
            let text = e.to_string();
            if !text.is_empty() && !message.contains(&text) {
                message.push_str(": ");
                message.push_str(&text);
            }
        }
        current = e.source();
    }
    DecisionsErrorCause {
        name: error_name(top).to_owned(),
        message: redactor.redact(&message),
        code,
    }
}

fn network_error(message: &str, error: &BoxError, redactor: &Redactor) -> DecisionsError {
    decisions_error(DecisionsErrorKind::Network, message)
        .with_source(sanitize_cause(error, redactor))
}

fn endpoint_unavailable(cause: Error) -> DecisionsError {
    let message = if cause.to_string().contains(GATE_ENV) {
        format!(
            "session.decisions requires the runtime's provider endpoint API. Set {GATE_ENV}=true in the runtime's environment (for in-process runtimes, in the host process environment before the runtime is loaded)."
        )
    } else {
        "Could not resolve the session's provider endpoint for the Decisions API.".to_owned()
    };
    decisions_error(DecisionsErrorKind::EndpointUnavailable, message).with_source(cause)
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

fn type_name(t: &ProviderEndpointType) -> &'static str {
    match t {
        ProviderEndpointType::Openai => "openai",
        ProviderEndpointType::Azure => "azure",
        ProviderEndpointType::Anthropic => "anthropic",
        ProviderEndpointType::Unknown => "unknown",
    }
}

fn transport_name(t: &ProviderEndpointTransport) -> &'static str {
    match t {
        ProviderEndpointTransport::Http => "http",
        ProviderEndpointTransport::Websockets => "websockets",
        ProviderEndpointTransport::Unknown => "unknown",
    }
}

/// Whether the raw authority of `base_url` contains `@`; the url crate drops empty userinfo.
fn raw_authority_has_userinfo(base_url: &str) -> bool {
    let trimmed = base_url.trim();
    let after_scheme = match trimmed.find(':') {
        Some(i) => trimmed[i + 1..].trim_start_matches(['/', '\\']),
        None => trimmed,
    };
    after_scheme
        .split(['/', '\\', '?', '#'])
        .next()
        .unwrap_or_default()
        .contains('@')
}

/// Builds the Decisions URL from the endpoint's base URL.
fn build_url(
    endpoint_type: &ProviderEndpointType,
    base_url: &str,
) -> Result<String, DecisionsError> {
    let url = reqwest::Url::parse(base_url).map_err(|_| {
        invalid_endpoint("The provider endpoint base URL is not a valid absolute URL.")
    })?;
    let scheme = url.scheme();
    if scheme != "http" && scheme != "https" {
        return Err(invalid_endpoint(
            "The provider endpoint base URL must use http or https.",
        ));
    }
    if !url.username().is_empty()
        || url.password().is_some_and(|p| !p.is_empty())
        || raw_authority_has_userinfo(base_url)
    {
        return Err(invalid_endpoint(
            "The provider endpoint base URL must not contain credentials.",
        ));
    }
    let host = url.host_str().unwrap_or_default();
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
            format!(
                "The session's provider type '{}' does not support the Decisions API.",
                type_name(&endpoint.r#type)
            ),
        ));
    }
    if let Some(transport) = &endpoint.transport
        && *transport != ProviderEndpointTransport::Http
    {
        return Err(decisions_error(
            DecisionsErrorKind::UnsupportedTransport,
            format!(
                "The session's provider transport '{}' is not supported by the Decisions API.",
                transport_name(transport)
            ),
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
            format!(
                "The provider session token is bound to model '{token_model}', but the request uses '{model}'. Set endpoint_model_id or request.model consistently."
            ),
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

/// Credentials that must never surface in errors: the API key, the session
/// token, every endpoint header value (also without a `Bearer` scheme), and
/// URL query values. Each is trimmed of SP/HTAB, kept only if at least
/// [`MIN_SECRET_LENGTH`] Unicode scalar values long, then expanded into its
/// wire encodings. Sorted longest first.
fn collect_secrets(endpoint: &ProviderEndpoint, url: &str) -> Vec<String> {
    let mut raw: IndexMap<String, ()> = IndexMap::new();
    let mut add = |value: &str| {
        let trimmed = trim_http_whitespace(value);
        if trimmed.chars().count() >= MIN_SECRET_LENGTH {
            raw.insert(trimmed.to_owned(), ());
        }
    };
    if let Some(api_key) = endpoint.api_key.as_deref() {
        add(api_key);
    }
    if let Some(token) = &endpoint.session_token {
        add(&token.token);
    }
    for value in endpoint.headers.values() {
        add(value);
        add(strip_bearer(value).unwrap_or(value));
    }
    if let Ok(parsed) = reqwest::Url::parse(url) {
        for (_, value) in parsed.query_pairs() {
            add(&value);
        }
    }
    let mut variants: IndexMap<String, ()> = IndexMap::new();
    for secret in raw.keys() {
        for variant in secret_variants(secret) {
            variants.insert(variant, ());
        }
    }
    let mut out: Vec<String> = variants.into_keys().collect();
    out.sort_by_key(|v| std::cmp::Reverse(v.encode_utf16().count()));
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

    /// Replaces every occurrence of any secret variant with `[REDACTED]`, longest first.
    fn redact(&self, text: &str) -> String {
        let mut result = text.to_owned();
        for (secret, regex) in &self.patterns {
            result = match regex {
                Some(regex) => regex.replace_all(&result, REDACTED).into_owned(),
                None => result.replace(secret.as_str(), REDACTED),
            };
        }
        result
    }
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

fn token_count(obj: Option<&Map<String, Value>>, key: &str) -> Shape<u64> {
    let Some(obj) = obj else { return Ok(0) };
    match obj.get(key) {
        None | Some(Value::Null) => Ok(0),
        Some(Value::Number(n)) => {
            if let Some(u) = n.as_u64() {
                return Ok(u);
            }
            match n.as_f64() {
                Some(f)
                    if f.is_finite() && f >= 0.0 && f.fract() == 0.0 && f <= u64::MAX as f64 =>
                {
                    Ok(f as u64)
                }
                _ => shape_err(format!("Expected token count '{key}'.")),
            }
        }
        Some(_) => shape_err(format!("Expected number '{key}'.")),
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
            probability: num(obj, "probability")?,
        }),
        "choice" => DecisionsAnswer::Choice(DecisionsChoiceAnswer {
            name,
            choice: choice_value(obj, "choice")?,
            confidence: num(obj, "confidence")?,
            probabilities: objects(obj, "probabilities")?
                .into_iter()
                .map(|p| {
                    Ok(DecisionsChoiceProbability {
                        value: choice_value(p, "value")?,
                        probability: num(p, "probability")?,
                    })
                })
                .collect::<Shape<_>>()?,
        }),
        "score" => DecisionsAnswer::Score(DecisionsScoreAnswer {
            name,
            score: num(obj, "score")?,
            confidence: num(obj, "confidence")?,
            probabilities: objects(obj, "probabilities")?
                .into_iter()
                .map(|p| {
                    let value = num(p, "value")?;
                    if value.fract() != 0.0 || value.abs() > i64::MAX as f64 {
                        return shape_err("Expected integer level 'value'.");
                    }
                    Ok(DecisionsScoreProbability {
                        label: string(p, "label")?,
                        value: value as i64,
                        probability: num(p, "probability")?,
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
    let input_details = usage.get("input_tokens_details").and_then(Value::as_object);
    let output_details = usage
        .get("output_tokens_details")
        .and_then(Value::as_object);
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
    client: Result<reqwest::Client, String>,
}

impl ReqwestTransport {
    fn new(client: Option<reqwest::Client>) -> Self {
        let client = match client {
            Some(client) => Ok(client),
            None => crate::copilot_request_handler::SHARED_HTTP_CLIENT
                .as_ref()
                .cloned()
                .map_err(|error| format!("HTTP TLS configuration failed: {error}")),
        };
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
            let client = self
                .client
                .as_ref()
                .map_err(|e| BoxError::from(e.clone()))?;
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
            let body = response
                .bytes_stream()
                .map(|chunk| chunk.map_err(|e| BoxError::from(e.without_url())))
                .boxed();
            Ok(InboundResponse {
                status,
                request_id,
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
        let mut end = max;
        while !text.is_char_boundary(end) {
            end -= 1;
        }
        text.truncate(end);
    }
    text
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
/// lookahead window as long as the longest secret is read past the cap and
/// redaction runs before truncation, so a credential that straddles the cap
/// is never partially retained. `Err` means the body could not be read.
async fn http_error(
    mut response: InboundResponse,
    redactor: &Redactor,
) -> Result<DecisionsError, BoxError> {
    let window = MAX_ERROR_BODY_BYTES + redactor.max_secret_bytes;
    let (bytes, exceeded) = read_limited(&mut response.body, window).await?;
    drop(response.body);
    let raw = decode_utf8(&bytes);
    let redacted = redactor.redact(&raw);
    let truncated = exceeded || bytes.len() > MAX_ERROR_BODY_BYTES;
    // Cut even when not truncated: replacing short secrets with "[REDACTED]" can grow the text.
    let body = truncate_utf8(redacted, MAX_ERROR_BODY_BYTES);

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
        error_message = redact("message");
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
    let mut error = decisions_error(DecisionsErrorKind::Http, message);
    error.http = Some(Box::new(HttpDetails {
        status,
        error_type,
        error_code,
        error_param,
        request_id: response.request_id,
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

    let endpoint = resolve_endpoint(&resolve, endpoint_model_id).await?;
    let mut plan = plan_attempt(&endpoint, &request.model)?;
    let body = Bytes::from(serde_json::to_vec(request)?);
    let mut response = send(transport, &plan, body.clone()).await?;

    if response.status == 401 || response.status == 403 {
        let original = read_http_error(response, &plan).await?;
        let refreshed_endpoint = resolve_endpoint(&resolve, endpoint_model_id).await?;
        let Ok(refreshed) = plan_attempt(&refreshed_endpoint, &request.model) else {
            return Err(original.into());
        };
        if refreshed.fingerprint == plan.fingerprint {
            return Err(original.into());
        }
        plan = refreshed;
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
    /// Cancel by dropping the returned future.
    pub async fn create(&self, request: DecisionsRequest) -> Result<DecisionsResult, Error> {
        self.create_with_options(request, DecisionsOptions::default())
            .await
    }

    /// **Experimental.** Like [`create`](Self::create), with per-call options.
    ///
    /// Cancel by dropping the returned future.
    pub async fn create_with_options(
        &self,
        request: DecisionsRequest,
        options: DecisionsOptions,
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
        ))
        .await
    }
}
