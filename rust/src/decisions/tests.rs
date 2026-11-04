#![cfg(test)]

use std::collections::{HashMap, VecDeque};
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, AtomicUsize, Ordering};
use std::sync::{Arc, Mutex};

use futures_util::stream;
use serde_json::json;
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::TcpListener;

use super::*;

// ============================================================================
// Fixtures
// ============================================================================

fn fixtures_dir() -> PathBuf {
    Path::new(env!("CARGO_MANIFEST_DIR")).join("../test/fixtures/decisions")
}

fn fixture_text(name: &str) -> String {
    let path = fixtures_dir().join(name);
    std::fs::read_to_string(&path)
        .unwrap_or_else(|e| panic!("failed to read fixture {}: {e}", path.display()))
}

fn fixture(name: &str) -> Value {
    serde_json::from_str(&fixture_text(name)).expect("fixture is valid JSON")
}

fn vectors(group: &str) -> Vec<Value> {
    let all = fixture("vectors.json");
    let list = all[group]
        .as_array()
        .unwrap_or_else(|| panic!("vector group '{group}' missing"))
        .clone();
    assert!(!list.is_empty(), "vector group '{group}' is empty");
    list
}

fn endpoint(value: &Value) -> ProviderEndpoint {
    serde_json::from_value(value.clone()).expect("vector endpoint deserializes")
}

fn openai_endpoint() -> ProviderEndpoint {
    endpoint(&json!({
        "type": "openai",
        "baseUrl": "https://api.openai.com/v1",
        "apiKey": "sk-test-0001",
        "headers": {}
    }))
}

fn openai_endpoint_with_key(key: &str) -> ProviderEndpoint {
    let mut ep = openai_endpoint();
    ep.api_key = Some(key.to_owned());
    ep
}

fn ok_body() -> String {
    fixture_text("response.full.json")
}

fn instructions(text: &str) -> DecisionsInstructions {
    DecisionsContent::Text(text.to_owned())
}

fn minimal_request() -> DecisionsRequest {
    DecisionsRequest::new(
        "gpt-6-luna",
        "The export button does nothing when clicked.",
        vec![DecisionsQuestion::Predicate(DecisionsPredicateQuestion {
            name: None,
            instructions: instructions("Is this ticket reporting a product defect?"),
        })],
    )
}

fn full_request() -> DecisionsRequest {
    let breakpoint = Some(DecisionsCacheBreakpoint);
    let raw = match json!({ "type": "future_item", "payload": { "anything": [1, true, null] } }) {
        Value::Object(map) => map,
        _ => unreachable!(),
    };
    let input = vec![
        DecisionsInputItem::Message(DecisionsMessage::Developer {
            content: "You triage incoming support tickets.".into(),
        }),
        DecisionsInputItem::Message(DecisionsMessage::System {
            content: vec![DecisionsInputText::new(
                "Answer only from the provided context.",
            )]
            .into(),
        }),
        DecisionsInputItem::Message(DecisionsMessage::User {
            content: vec![
                DecisionsUserContentPart::Text(DecisionsInputText {
                    text: "Ticket #4821: the export button does nothing.".into(),
                    prompt_cache_breakpoint: breakpoint,
                }),
                DecisionsUserContentPart::Image(DecisionsInputImage {
                    image_url: "data:image/png;base64,iVBORw0KGgo=".into(),
                    detail: Some(DecisionsImageDetail::High),
                    prompt_cache_breakpoint: breakpoint,
                }),
                DecisionsUserContentPart::Json(DecisionsInputJson {
                    data: json!({ "plan": "enterprise", "seats": 120 }),
                }),
                DecisionsUserContentPart::Json(DecisionsInputJson {
                    data: json!([{ "id": 1 }, { "id": 2 }]),
                }),
            ]
            .into(),
        }),
        DecisionsInputItem::UntypedMessage(DecisionsMessage::User {
            content: vec![
                DecisionsUserContentPart::Text(DecisionsInputText::new(
                    "Customer replied: still broken after refresh.",
                )),
                DecisionsUserContentPart::Image(DecisionsInputImage {
                    image_url: "https://example.com/full.png".into(),
                    detail: Some(DecisionsImageDetail::Original),
                    prompt_cache_breakpoint: None,
                }),
            ]
            .into(),
        }),
        DecisionsInputItem::Message(DecisionsMessage::Assistant {
            content: vec![
                DecisionsAssistantContentPart::Text(DecisionsOutputText {
                    text: "I will look up the account.".into(),
                }),
                DecisionsAssistantContentPart::Refusal(DecisionsRefusalContent {
                    refusal: "I cannot share internal notes.".into(),
                }),
            ]
            .into(),
            phase: Some(DecisionsMessagePhase::Commentary),
        }),
        DecisionsInputItem::FunctionCall(DecisionsFunctionCall {
            call_id: "call_1".into(),
            name: "lookup_account".into(),
            arguments: "{\"ticket\":4821}".into(),
            id: Some("fc_1".into()),
            namespace: Some("crm".into()),
            status: Some(DecisionsItemStatus::Completed),
        }),
        DecisionsInputItem::FunctionCallOutput(DecisionsFunctionCallOutput {
            call_id: "call_1".into(),
            output: "{\"tier\":\"enterprise\"}".into(),
            id: Some("fco_1".into()),
            status: Some(DecisionsItemStatus::Completed),
        }),
        DecisionsInputItem::FunctionCallOutput(DecisionsFunctionCallOutput {
            call_id: "call_2".into(),
            output: vec![
                DecisionsToolOutputPart::Text(DecisionsInputText::new("Screenshot attached.")),
                DecisionsToolOutputPart::Image(DecisionsInputImage {
                    image_url: "https://example.com/shot.png".into(),
                    detail: Some(DecisionsImageDetail::Low),
                    prompt_cache_breakpoint: None,
                }),
            ]
            .into(),
            id: None,
            status: None,
        }),
        DecisionsInputItem::CustomToolCall(DecisionsCustomToolCall {
            call_id: "call_3".into(),
            name: "grep_logs".into(),
            input: "export failed".into(),
            id: Some("ctc_1".into()),
            namespace: Some("ops".into()),
            status: Some(DecisionsItemStatus::Incomplete),
        }),
        DecisionsInputItem::CustomToolCallOutput(DecisionsCustomToolCallOutput {
            call_id: "call_3".into(),
            output: "3 matches".into(),
            id: Some("ctco_1".into()),
            status: Some(DecisionsItemStatus::InProgress),
        }),
        DecisionsInputItem::Raw(raw),
    ];
    let questions = vec![
        DecisionsQuestion::Predicate(DecisionsPredicateQuestion {
            name: Some("is_bug".into()),
            instructions: instructions("Is this ticket reporting a product defect?"),
        }),
        DecisionsQuestion::Choice(DecisionsChoiceQuestion {
            name: Some("team".into()),
            instructions: instructions("Which team should own this ticket?"),
            choices: vec![
                DecisionsChoiceOption {
                    value: "frontend".into(),
                    description: Some("UI and client code".into()),
                },
                DecisionsChoiceOption {
                    value: "backend".into(),
                    description: None,
                },
            ],
        }),
        DecisionsQuestion::Choice(DecisionsChoiceQuestion {
            name: None,
            instructions: vec![
                DecisionsQuestionPart::Text(DecisionsQuestionText {
                    text: "Should we escalate?".into(),
                }),
                DecisionsQuestionPart::Image(DecisionsQuestionImage {
                    image_url: "https://example.com/policy.png".into(),
                    detail: Some(DecisionsImageDetail::Auto),
                }),
                DecisionsQuestionPart::Json(DecisionsInputJson {
                    data: json!({ "sla_hours": 4 }),
                }),
            ]
            .into(),
            choices: vec![
                DecisionsChoiceOption {
                    value: true.into(),
                    description: Some("Escalate now".into()),
                },
                DecisionsChoiceOption {
                    value: false.into(),
                    description: None,
                },
            ],
        }),
        DecisionsQuestion::Score(DecisionsScoreQuestion {
            name: Some("severity".into()),
            instructions: instructions("How severe is the impact?"),
            levels: vec![
                DecisionsScoreLevel {
                    label: "low".into(),
                    description: Some("Cosmetic".into()),
                },
                DecisionsScoreLevel {
                    label: "medium".into(),
                    description: None,
                },
                DecisionsScoreLevel {
                    label: "high".into(),
                    description: Some("Blocks work".into()),
                },
            ],
        }),
    ];
    DecisionsRequest {
        prompt_cache_key: Some("support-triage-v1".into()),
        prompt_cache_options: Some(DecisionsPromptCacheOptions {
            mode: Some(DecisionsPromptCacheMode::Explicit),
            ttl: Some(DecisionsPromptCacheTtl::ThirtyMinutes),
        }),
        safety_identifier: Some("user-7f3a9c".into()),
        ..DecisionsRequest::new("gpt-6-luna", input, questions)
    }
}

// ============================================================================
// Fakes
// ============================================================================

type Events = Arc<Mutex<Vec<String>>>;
type Responder = Arc<dyn Fn(&Events) -> InboundResponse + Send + Sync>;

struct RecordedCall {
    url: String,
    headers: IndexMap<String, String>,
    body: Bytes,
}

/// Scripted transport. Reuses the last responder once the script runs out.
struct FakeTransport {
    responders: Vec<Responder>,
    index: AtomicUsize,
    calls: Mutex<Vec<RecordedCall>>,
    events: Events,
}

impl FakeTransport {
    fn new(responders: Vec<Responder>) -> Self {
        Self {
            responders,
            index: AtomicUsize::new(0),
            calls: Mutex::new(Vec::new()),
            events: Arc::default(),
        }
    }

    fn call_count(&self) -> usize {
        self.calls.lock().unwrap().len()
    }
}

impl Transport for FakeTransport {
    fn send<'a>(
        &'a self,
        url: &'a str,
        headers: &'a IndexMap<String, String>,
        body: Bytes,
    ) -> BoxFuture<'a, Result<InboundResponse, BoxError>> {
        Box::pin(async move {
            let index = self.index.fetch_add(1, Ordering::SeqCst);
            self.events.lock().unwrap().push(format!("send{index}"));
            self.calls.lock().unwrap().push(RecordedCall {
                url: url.to_owned(),
                headers: headers.clone(),
                body,
            });
            let responder = &self.responders[index.min(self.responders.len() - 1)];
            Ok(responder(&self.events))
        })
    }
}

/// A transport whose send always fails with the given error factory.
struct FailingTransport(Box<dyn Fn() -> BoxError + Send + Sync>);

impl Transport for FailingTransport {
    fn send<'a>(
        &'a self,
        _url: &'a str,
        _headers: &'a IndexMap<String, String>,
        _body: Bytes,
    ) -> BoxFuture<'a, Result<InboundResponse, BoxError>> {
        Box::pin(async move { Err((self.0)()) })
    }
}

fn body_stream(bytes: impl Into<Vec<u8>>) -> BodyStream {
    // Split into 16 KiB chunks to exercise chunked reads.
    let bytes: Vec<u8> = bytes.into();
    let chunks: Vec<Result<Bytes, BoxError>> = bytes
        .chunks(16 * 1024)
        .map(|c| Ok(Bytes::copy_from_slice(c)))
        .collect();
    stream::iter(chunks).boxed()
}

fn response(status: u16, body: impl Into<Vec<u8>>) -> InboundResponse {
    InboundResponse {
        status,
        request_id: None,
        body: body_stream(body),
    }
}

fn respond(status: u16, body: impl Into<String>) -> Responder {
    let body: String = body.into();
    Arc::new(move |_| response(status, body.clone()))
}

fn respond_with_id(status: u16, body: impl Into<String>, request_id: &str) -> Responder {
    let body: String = body.into();
    let request_id = request_id.to_owned();
    Arc::new(move |_| InboundResponse {
        request_id: Some(request_id.clone()),
        ..response(status, body.clone())
    })
}

type ResolveResult = Result<ProviderEndpoint, Error>;
type ResolveStep = Box<dyn FnOnce() -> ResolveResult + Send>;

/// Scripted endpoint resolver. Reuses the last endpoint once the script runs out.
struct Resolver {
    script: Mutex<VecDeque<ResolveStep>>,
    last: Mutex<Option<ProviderEndpoint>>,
    seen: Mutex<Vec<Option<String>>>,
}

impl Resolver {
    fn endpoints(endpoints: Vec<ProviderEndpoint>) -> Self {
        Self::script(
            endpoints
                .into_iter()
                .map(|ep| Box::new(move || Ok(ep)) as ResolveStep)
                .collect(),
        )
    }

    fn script(script: Vec<ResolveStep>) -> Self {
        Self {
            script: Mutex::new(script.into()),
            last: Mutex::new(None),
            seen: Mutex::new(Vec::new()),
        }
    }

    fn resolve(&self, model_id: Option<String>) -> std::future::Ready<ResolveResult> {
        self.seen.lock().unwrap().push(model_id);
        let next = self.script.lock().unwrap().pop_front();
        let result = match next {
            Some(step) => step(),
            None => Ok(self
                .last
                .lock()
                .unwrap()
                .clone()
                .expect("resolver script exhausted")),
        };
        if let Ok(ep) = &result {
            *self.last.lock().unwrap() = Some(ep.clone());
        }
        std::future::ready(result)
    }

    fn seen(&self) -> Vec<Option<String>> {
        self.seen.lock().unwrap().clone()
    }
}

async fn run(
    resolver: &Resolver,
    transport: &dyn Transport,
    request: &DecisionsRequest,
    endpoint_model_id: Option<&str>,
) -> Result<DecisionsResult, Error> {
    Box::pin(execute(
        |m| resolver.resolve(m),
        transport,
        request,
        endpoint_model_id,
    ))
    .await
}

async fn run_err(
    resolver: &Resolver,
    transport: &dyn Transport,
    request: &DecisionsRequest,
) -> Error {
    run(resolver, transport, request, None)
        .await
        .expect_err("expected the call to fail")
}

fn details(error: &Error) -> &DecisionsError {
    error
        .decisions_error()
        .unwrap_or_else(|| panic!("expected a Decisions error, got {error:?}"))
}

fn kind_of(error: &Error) -> &'static str {
    let details = details(error);
    assert_eq!(error.kind(), &ErrorKind::Decisions(details.kind()));
    details.kind().as_str()
}

fn cause_of(error: &Error) -> &DecisionsErrorCause {
    std::error::Error::source(details(error))
        .and_then(|e| e.downcast_ref::<DecisionsErrorCause>())
        .expect("sanitized cause")
}

struct DropFlag(Arc<AtomicBool>);

impl Drop for DropFlag {
    fn drop(&mut self) {
        self.0.store(true, Ordering::SeqCst);
    }
}

// ============================================================================
// Serialization
// ============================================================================

#[test]
fn serializes_full_request_to_golden_fixture() {
    let actual = serde_json::to_value(full_request()).unwrap();
    assert_eq!(actual, fixture("request.full.json"));
}

#[test]
fn serializes_minimal_request() {
    let actual = serde_json::to_value(minimal_request()).unwrap();
    assert_eq!(actual, fixture("request.minimal.json"));
}

#[test]
fn omits_none_fields() {
    let request = DecisionsRequest::new(
        "gpt-6-luna",
        vec![
            DecisionsInputItem::Message(DecisionsMessage::User {
                content: vec![DecisionsUserContentPart::Image(DecisionsInputImage {
                    image_url: "https://example.com/a.png".into(),
                    detail: None,
                    prompt_cache_breakpoint: None,
                })]
                .into(),
            }),
            DecisionsInputItem::Message(DecisionsMessage::Assistant {
                content: "ok".into(),
                phase: None,
            }),
            DecisionsInputItem::FunctionCall(DecisionsFunctionCall {
                call_id: "c".into(),
                name: "n".into(),
                arguments: "{}".into(),
                id: None,
                namespace: None,
                status: None,
            }),
        ],
        vec![
            DecisionsQuestion::Choice(DecisionsChoiceQuestion {
                name: None,
                instructions: instructions("q"),
                choices: vec![DecisionsChoiceOption {
                    value: "a".into(),
                    description: None,
                }],
            }),
            DecisionsQuestion::Score(DecisionsScoreQuestion {
                name: None,
                instructions: instructions("s"),
                levels: vec![DecisionsScoreLevel {
                    label: "x".into(),
                    description: None,
                }],
            }),
        ],
    );
    assert_eq!(
        serde_json::to_value(&request).unwrap(),
        json!({
            "model": "gpt-6-luna",
            "input": [
                {
                    "type": "message",
                    "role": "user",
                    "content": [{ "type": "input_image", "image_url": "https://example.com/a.png" }]
                },
                { "type": "message", "role": "assistant", "content": "ok" },
                { "type": "function_call", "call_id": "c", "name": "n", "arguments": "{}" }
            ],
            "questions": [
                { "type": "choice", "instructions": "q", "choices": [{ "value": "a" }] },
                { "type": "score", "instructions": "s", "levels": [{ "label": "x" }] }
            ]
        })
    );
    assert_eq!(
        serde_json::to_value(DecisionsPromptCacheOptions::default()).unwrap(),
        json!({})
    );
}

#[test]
fn choice_values_keep_bool_and_string_types() {
    assert_eq!(
        serde_json::to_value(DecisionsChoiceValue::from(true)).unwrap(),
        json!(true)
    );
    assert_eq!(
        serde_json::to_value(DecisionsChoiceValue::from("true")).unwrap(),
        json!("true")
    );
    let parsed: DecisionsChoiceValue = serde_json::from_value(json!(false)).unwrap();
    assert_eq!(parsed, DecisionsChoiceValue::Bool(false));
}

#[tokio::test]
async fn requires_model_before_resolving() {
    let resolver = Resolver::endpoints(vec![openai_endpoint()]);
    let transport = FakeTransport::new(vec![respond(200, ok_body())]);
    let mut request = minimal_request();
    request.model = String::new();
    let error = run_err(&resolver, &transport, &request).await;
    assert_eq!(error.kind(), &ErrorKind::InvalidConfig);
    assert!(error.to_string().contains("model is required"));
    assert!(resolver.seen().is_empty());
    assert_eq!(transport.call_count(), 0);
}

// ============================================================================
// Response parsing
// ============================================================================

#[test]
fn parses_full_response_fixture() {
    let result = parse_result(&fixture("response.full.json")).unwrap_or_else(|e| panic!("{e}"));
    assert_eq!(result.model, "gpt-6-luna-2026-10-01");
    assert_eq!(
        result.usage,
        DecisionsUsage {
            input_tokens: 812,
            input_tokens_details: DecisionsInputTokensDetails {
                cached_tokens: 512,
                cache_write_tokens: 128,
            },
            output_tokens: 24,
            output_tokens_details: DecisionsOutputTokensDetails {
                reasoning_tokens: 16
            },
            total_tokens: 836,
        }
    );
    assert_eq!(result.answers.len(), 6);
    assert_eq!(
        result.answers[0],
        DecisionsAnswer::Predicate(DecisionsPredicateAnswer {
            name: Some("is_bug".into()),
            probability: 0.93,
        })
    );
    match &result.answers[1] {
        DecisionsAnswer::Choice(a) => {
            assert_eq!(a.name.as_deref(), Some("team"));
            assert_eq!(a.choice, DecisionsChoiceValue::String("frontend".into()));
            assert_eq!(a.confidence, 0.81);
        }
        other => panic!("expected choice, got {other:?}"),
    }
    match &result.answers[2] {
        DecisionsAnswer::Choice(a) => {
            assert_eq!(a.name, None);
            assert_eq!(a.choice, DecisionsChoiceValue::Bool(true));
            let values: Vec<_> = a.probabilities.iter().map(|p| p.value.clone()).collect();
            assert_eq!(
                values,
                vec![
                    DecisionsChoiceValue::Bool(true),
                    DecisionsChoiceValue::Bool(false)
                ]
            );
        }
        other => panic!("expected choice, got {other:?}"),
    }
    match &result.answers[3] {
        DecisionsAnswer::Score(a) => {
            assert_eq!(a.name.as_deref(), Some("severity"));
            assert_eq!(a.score, 1.37);
            let values: Vec<_> = a.probabilities.iter().map(|p| p.value).collect();
            assert_eq!(values, vec![0, 1, 2]);
            assert_eq!(a.probabilities[1].label, "medium");
        }
        other => panic!("expected score, got {other:?}"),
    }
    assert_eq!(
        result.answers[4],
        DecisionsAnswer::Refusal(DecisionsRefusalAnswer {
            name: Some("pii_check".into())
        })
    );
    assert_eq!(
        result.answers[5],
        DecisionsAnswer::Unknown {
            name: Some("future_kind".into()),
            wire_type: "ranking".into(),
            raw: json!({ "type": "ranking", "name": "future_kind", "ranked": ["a", "b"] }),
        }
    );
    assert_eq!(result.answers[5].name(), Some("future_kind"));
}

#[test]
fn rejects_malformed_responses() {
    let bad = [
        Value::Null,
        json!([]),
        json!({ "answers": [], "model": "m" }),
        json!({ "answers": [{ "type": "predicate" }], "model": "m", "usage": {} }),
        json!({
            "answers": [{ "type": "choice", "choice": 3, "confidence": 1, "probabilities": [] }],
            "model": "m",
            "usage": {}
        }),
        json!({
            "answers": [{
                "type": "score",
                "score": 1,
                "confidence": 1,
                "probabilities": [{ "label": "a", "value": 0.5, "probability": 1 }]
            }],
            "model": "m",
            "usage": {}
        }),
    ];
    for value in bad {
        let Err(error) = parse_result(&value) else {
            panic!("expected failure for {value}");
        };
        assert_eq!(error.kind(), DecisionsErrorKind::InvalidResponse, "{value}");
    }
    // Missing token counts default to zero.
    let ok = parse_result(&json!({ "answers": [], "model": "m", "usage": {} }))
        .unwrap_or_else(|e| panic!("{e}"));
    assert_eq!(ok.usage, DecisionsUsage::default());
}

// ============================================================================
// Vectors
// ============================================================================

#[test]
fn vectors_url() {
    for v in vectors("url") {
        let name = v["name"].as_str().unwrap();
        let ep = endpoint(&v["endpoint"]);
        let result = build_url(&ep.r#type, &ep.base_url);
        match v["error"].as_str() {
            Some(kind) => {
                let Err(error) = result else {
                    panic!("{name}: expected error");
                };
                assert_eq!(error.kind().as_str(), kind, "{name}");
                assert!(!error.message().contains("secretpassword"), "{name}");
            }
            None => assert_eq!(
                result.unwrap_or_else(|e| panic!("{name}: {e}")),
                v["expected"].as_str().unwrap(),
                "{name}"
            ),
        }
    }
}

#[test]
fn vectors_headers() {
    for v in vectors("headers") {
        let name = v["name"].as_str().unwrap();
        let ep = endpoint(&v["endpoint"]);
        match v["error"].as_str() {
            Some(kind) => {
                let Err(error) = plan_attempt(&ep, "gpt-6-luna") else {
                    panic!("{name}: expected error");
                };
                assert_eq!(error.kind().as_str(), kind, "{name}");
            }
            None => {
                let expected: HashMap<String, String> =
                    serde_json::from_value(v["expected"].clone()).unwrap();
                let plan =
                    plan_attempt(&ep, "gpt-6-luna").unwrap_or_else(|e| panic!("{name}: {e}"));
                let planned: HashMap<String, String> = plan.headers.into_iter().collect();
                assert_eq!(planned, expected, "{name}");
                let built: HashMap<String, String> = build_headers(&ep)
                    .unwrap_or_else(|e| panic!("{name}: {e}"))
                    .into_iter()
                    .collect();
                assert_eq!(built, expected, "{name}");
            }
        }
    }
}

#[test]
fn vectors_preflight() {
    for v in vectors("preflight") {
        let name = v["name"].as_str().unwrap();
        let ep = endpoint(&v["endpoint"]);
        let model = v["model"].as_str().unwrap();
        match v["error"].as_str() {
            Some(kind) => {
                let Err(error) = plan_attempt(&ep, model) else {
                    panic!("{name}: expected error");
                };
                assert_eq!(error.kind().as_str(), kind, "{name}");
            }
            None => {
                if let Err(e) = plan_attempt(&ep, model) {
                    panic!("{name}: {e}");
                }
            }
        }
    }
}

#[tokio::test]
async fn vectors_retry() {
    for v in vectors("retry") {
        let name = v["name"].as_str().unwrap();
        let first = endpoint(&v["first"]);
        let second = endpoint(&v["second"]);
        let second_url = build_url(&second.r#type, &second.base_url).ok();
        let resolver = Resolver::endpoints(vec![first, second]);
        let transport = FakeTransport::new(vec![
            respond(401, r#"{"error":{"type":"auth","code":"expired"}}"#),
            respond(200, ok_body()),
        ]);
        let result = run(&resolver, &transport, &minimal_request(), None).await;
        if v["retry"].as_bool().unwrap() {
            let result = result.unwrap_or_else(|e| panic!("{name}: {e}"));
            assert_eq!(result.model, "gpt-6-luna-2026-10-01", "{name}");
            let calls = transport.calls.lock().unwrap();
            assert_eq!(calls.len(), 2, "{name}");
            assert_eq!(Some(calls[1].url.clone()), second_url, "{name}");
        } else {
            let error = result.expect_err(name);
            assert_eq!(kind_of(&error), "http", "{name}");
            let d = details(&error);
            assert_eq!(d.status(), Some(401), "{name}");
            assert_eq!(d.error_code(), Some("expired"), "{name}");
            assert_eq!(transport.call_count(), 1, "{name}");
        }
        assert_eq!(resolver.seen().len(), 2, "{name}");
    }
}

#[tokio::test]
async fn retries_at_most_once() {
    let resolver = Resolver::endpoints(vec![
        openai_endpoint(),
        openai_endpoint_with_key("sk-test-0002"),
        openai_endpoint_with_key("sk-test-0003"),
    ]);
    let transport = FakeTransport::new(vec![respond(403, "{}")]);
    let error = run_err(&resolver, &transport, &minimal_request()).await;
    assert_eq!(kind_of(&error), "http");
    assert_eq!(details(&error).status(), Some(403));
    assert_eq!(transport.call_count(), 2);
    assert_eq!(resolver.seen().len(), 2);
}

#[tokio::test]
async fn vectors_redaction_length() {
    for v in vectors("redactionLength") {
        let name = v["name"].as_str().unwrap();
        let value = v["value"].as_str().unwrap();
        let trimmed = value.trim_matches([' ', '\t']);
        let resolver = Resolver::endpoints(vec![openai_endpoint_with_key(value)]);
        let transport = FakeTransport::new(vec![respond(400, format!("echo:{trimmed}:end"))]);
        let error = run_err(&resolver, &transport, &minimal_request()).await;
        let expected = if v["isSecret"].as_bool().unwrap() {
            "echo:[REDACTED]:end".to_owned()
        } else {
            format!("echo:{trimmed}:end")
        };
        assert_eq!(details(&error).body(), Some(expected.as_str()), "{name}");
    }
}

#[tokio::test]
async fn vectors_redaction() {
    for v in vectors("redaction") {
        let name = v["name"].as_str().unwrap();
        let status = u16::try_from(v["status"].as_u64().unwrap()).unwrap();
        let mut payload = String::new();
        if let Some(prefix) = v.get("bodyPrefix") {
            let c = prefix["char"].as_str().unwrap();
            let count = usize::try_from(prefix["count"].as_u64().unwrap()).unwrap();
            payload.push_str(&c.repeat(count));
        }
        let mut body = v["body"].as_str().unwrap().to_owned();
        if let Some(repeat) = v.get("bodyRepeat") {
            let text = repeat["text"].as_str().unwrap();
            let count = usize::try_from(repeat["count"].as_u64().unwrap()).unwrap();
            body = body.replace("{{repeat}}", &text.repeat(count));
        }
        payload.push_str(&body);

        let resolver = Resolver::endpoints(vec![endpoint(&v["endpoint"])]);
        let transport = FakeTransport::new(vec![respond_with_id(status, payload, "req_123")]);
        let error = run_err(&resolver, &transport, &minimal_request()).await;
        assert_eq!(kind_of(&error), "http", "{name}");
        let d = details(&error);
        assert_eq!(d.status(), Some(status), "{name}");
        assert_eq!(d.request_id(), Some("req_123"), "{name}");
        assert_eq!(d.error_code(), v["errorCode"].as_str(), "{name}");
        assert_eq!(d.error_type(), v["errorType"].as_str(), "{name}");
        if let Some(truncated) = v.get("bodyTruncated").and_then(Value::as_bool) {
            assert_eq!(d.body_truncated(), Some(truncated), "{name}");
        }
        if let Some(max) = v.get("maxBodyBytes").and_then(Value::as_u64) {
            assert!(d.body().unwrap_or_default().len() as u64 <= max, "{name}");
        }
        let surfaces = [
            d.message().to_owned(),
            d.body().unwrap_or_default().to_owned(),
            d.error_code().unwrap_or_default().to_owned(),
            d.error_type().unwrap_or_default().to_owned(),
            d.error_param().unwrap_or_default().to_owned(),
            d.to_string(),
            format!("{d:?}"),
            error.to_string(),
            format!("{error:?}"),
        ]
        .join("\n");
        for secret in v["mustNotContain"].as_array().unwrap() {
            let secret = secret.as_str().unwrap();
            assert!(!surfaces.contains(secret), "{name}: leaked {secret:?}");
        }
        if d.body_truncated() != Some(true) {
            assert!(d.body().unwrap().contains(REDACTED), "{name}");
        }
    }
}

// ============================================================================
// create
// ============================================================================

#[tokio::test]
async fn posts_serialized_request_with_planned_headers() {
    let resolver = Resolver::endpoints(vec![openai_endpoint()]);
    let transport = FakeTransport::new(vec![respond(200, ok_body())]);
    let result = run(&resolver, &transport, &full_request(), None)
        .await
        .unwrap();
    assert_eq!(result.answers.len(), 6);
    assert_eq!(resolver.seen(), vec![None]);
    let calls = transport.calls.lock().unwrap();
    assert_eq!(calls.len(), 1);
    assert_eq!(calls[0].url, "https://api.openai.com/v1/decisions");
    let expected: IndexMap<String, String> = [
        ("authorization", "Bearer sk-test-0001"),
        ("content-type", "application/json"),
        ("accept", "application/json"),
    ]
    .into_iter()
    .map(|(k, v)| (k.to_owned(), v.to_owned()))
    .collect();
    assert_eq!(calls[0].headers, expected);
    let body: Value = serde_json::from_slice(&calls[0].body).unwrap();
    assert_eq!(body, fixture("request.full.json"));
}

#[tokio::test]
async fn passes_endpoint_model_id_verbatim() {
    let resolver = Resolver::endpoints(vec![openai_endpoint()]);
    let transport = FakeTransport::new(vec![respond(200, ok_body())]);
    run(
        &resolver,
        &transport,
        &minimal_request(),
        Some("endpoint-model"),
    )
    .await
    .unwrap();
    assert_eq!(resolver.seen(), vec![Some("endpoint-model".to_owned())]);
}

#[tokio::test]
async fn calls_get_endpoint_on_every_create() {
    let resolver = Resolver::endpoints(vec![openai_endpoint()]);
    let transport = FakeTransport::new(vec![respond(200, ok_body())]);
    run(&resolver, &transport, &minimal_request(), None)
        .await
        .unwrap();
    run(&resolver, &transport, &minimal_request(), None)
        .await
        .unwrap();
    assert_eq!(resolver.seen().len(), 2);
}

#[tokio::test]
async fn maps_openai_error_envelope() {
    let resolver = Resolver::endpoints(vec![openai_endpoint()]);
    let transport = FakeTransport::new(vec![respond_with_id(
        400,
        fixture("error.json").to_string(),
        "req_9",
    )]);
    let error = run_err(&resolver, &transport, &minimal_request()).await;
    assert_eq!(kind_of(&error), "http");
    let d = details(&error);
    assert_eq!(d.status(), Some(400));
    assert_eq!(d.error_type(), Some("invalid_request_error"));
    assert_eq!(d.error_code(), Some("unsupported_model"));
    assert_eq!(d.error_param(), Some("model"));
    assert_eq!(d.request_id(), Some("req_9"));
    assert_eq!(d.body_truncated(), Some(false));
    assert!(d.message().contains("does not support the Decisions API"));
    assert_eq!(error.to_string(), d.message());
}

#[tokio::test]
async fn bounds_large_error_bodies() {
    let resolver = Resolver::endpoints(vec![openai_endpoint()]);
    let big = "x".repeat(MAX_ERROR_BODY_BYTES + 1000);
    let transport = FakeTransport::new(vec![respond(500, big)]);
    let error = run_err(&resolver, &transport, &minimal_request()).await;
    let d = details(&error);
    assert_eq!(d.kind(), DecisionsErrorKind::Http);
    assert_eq!(d.body_truncated(), Some(true));
    assert_eq!(d.body().unwrap().len(), MAX_ERROR_BODY_BYTES);
}

#[test]
fn truncates_error_body_on_char_boundary() {
    // A 2-, 3- and 4-byte character straddling the limit is dropped entirely.
    for (ch, width) in [('é', 2), ('€', 3), ('😀', 4)] {
        for overhang in 1..width {
            let prefix = MAX_ERROR_BODY_BYTES + overhang - width;
            let text = format!("{}{ch}tail", "x".repeat(prefix));
            let truncated = truncate_utf8(text, MAX_ERROR_BODY_BYTES);
            assert_eq!(truncated.len(), prefix, "{ch} overhang {overhang}");
            assert!(truncated.len() <= MAX_ERROR_BODY_BYTES);
            assert!(!truncated.contains('\u{FFFD}'));
            assert!(truncated.bytes().all(|b| b == b'x'));
        }
    }
    // A character ending exactly at the limit is kept.
    let text = format!("{}é!", "x".repeat(MAX_ERROR_BODY_BYTES - 2));
    let truncated = truncate_utf8(text, MAX_ERROR_BODY_BYTES);
    assert_eq!(truncated.len(), MAX_ERROR_BODY_BYTES);
    assert!(truncated.ends_with('é'));
}

#[tokio::test]
async fn error_body_split_char_is_dropped_not_replaced() {
    // Invalid UTF-8 early in the body is decoded lossily; the split trailing
    // character at the 64 KiB boundary is dropped without U+FFFD.
    let mut raw = vec![0xFFu8];
    raw.extend(std::iter::repeat_n(b'x', MAX_ERROR_BODY_BYTES - 4));
    raw.extend("😀".as_bytes());
    raw.extend(b"more");
    let responder: Responder = Arc::new(move |_| response(500, raw.clone()));
    let resolver = Resolver::endpoints(vec![openai_endpoint()]);
    let transport = FakeTransport::new(vec![responder]);
    let error = run_err(&resolver, &transport, &minimal_request()).await;
    let d = details(&error);
    let body = d.body().unwrap();
    assert_eq!(d.body_truncated(), Some(true));
    assert!(body.len() <= MAX_ERROR_BODY_BYTES);
    assert!(body.starts_with('\u{FFFD}'));
    assert_eq!(body.matches('\u{FFFD}').count(), 1);
    assert!(!body.contains('😀'));
    assert!(body.ends_with('x'));
}

#[tokio::test]
async fn reports_non_json_success_as_invalid_response() {
    let resolver = Resolver::endpoints(vec![openai_endpoint()]);
    let transport = FakeTransport::new(vec![respond(200, "<html>")]);
    let error = run_err(&resolver, &transport, &minimal_request()).await;
    assert_eq!(kind_of(&error), "invalidResponse");
}

#[derive(Debug)]
struct LeakyError {
    message: String,
    source: std::io::Error,
}

impl fmt::Display for LeakyError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(&self.message)
    }
}

impl std::error::Error for LeakyError {
    fn source(&self) -> Option<&(dyn std::error::Error + 'static)> {
        Some(&self.source)
    }
}

#[tokio::test]
async fn wraps_transport_failures_with_sanitized_cause() {
    let resolver = Resolver::endpoints(vec![openai_endpoint()]);
    let transport = FailingTransport(Box::new(|| {
        Box::new(LeakyError {
            message: "fetch failed for https://api.openai.com/v1/decisions?key=sk-test-0001 with Bearer sk-test-0001".into(),
            source: std::io::Error::new(
                std::io::ErrorKind::ConnectionReset,
                "connection reset (token sk-test-0001)",
            ),
        })
    }));
    let error = run_err(&resolver, &transport, &minimal_request()).await;
    assert_eq!(kind_of(&error), "network");
    assert_eq!(
        details(&error).message(),
        "The Decisions request could not be sent."
    );
    let cause = cause_of(&error);
    assert_eq!(cause.name(), "Error");
    assert_eq!(cause.code(), Some("ConnectionReset"));
    assert!(
        !cause.message().contains("sk-test-0001"),
        "{}",
        cause.message()
    );
    assert!(cause.message().contains(REDACTED));
    assert!(cause.message().contains("connection reset"));
    assert!(std::error::Error::source(cause).is_none());
    assert!(!format!("{error:?}").contains("sk-test-0001"));
}

#[tokio::test]
async fn sanitized_cause_names_io_errors() {
    let resolver = Resolver::endpoints(vec![openai_endpoint()]);
    let transport = FailingTransport(Box::new(|| {
        Box::new(std::io::Error::new(
            std::io::ErrorKind::TimedOut,
            "timed out",
        ))
    }));
    let error = run_err(&resolver, &transport, &minimal_request()).await;
    let cause = cause_of(&error);
    assert_eq!(cause.name(), "IoError");
    assert_eq!(cause.code(), Some("TimedOut"));
    assert_eq!(cause.message(), "timed out");
}

#[tokio::test]
async fn refresh_failure_is_endpoint_unavailable() {
    let resolver = Resolver::script(vec![
        Box::new(|| Ok(openai_endpoint())),
        Box::new(|| {
            Err(Error::with_message(
                ErrorKind::Rpc { code: -32603 },
                "runtime went away",
            ))
        }),
    ]);
    let transport = FakeTransport::new(vec![respond(401, "{}")]);
    let error = run_err(&resolver, &transport, &minimal_request()).await;
    assert_eq!(kind_of(&error), "endpointUnavailable");
    assert_eq!(transport.call_count(), 1);
}

#[tokio::test]
async fn invalid_refreshed_endpoint_returns_original_error() {
    let mut anthropic = openai_endpoint();
    anthropic.r#type = ProviderEndpointType::Anthropic;
    let resolver = Resolver::endpoints(vec![openai_endpoint(), anthropic]);
    let transport = FakeTransport::new(vec![respond(401, "{}")]);
    let error = run_err(&resolver, &transport, &minimal_request()).await;
    assert_eq!(kind_of(&error), "http");
    assert_eq!(details(&error).status(), Some(401));
    assert_eq!(transport.call_count(), 1);
}

#[tokio::test]
async fn rejects_unsupported_providers_before_sending() {
    let resolver = Resolver::endpoints(vec![endpoint(&json!({
        "type": "anthropic",
        "baseUrl": "https://api.anthropic.com",
        "headers": {}
    }))]);
    let transport = FakeTransport::new(vec![respond(200, ok_body())]);
    let error = run_err(&resolver, &transport, &minimal_request()).await;
    assert_eq!(kind_of(&error), "unsupportedProvider");
    assert_eq!(transport.call_count(), 0);
}

#[tokio::test]
async fn drains_401_body_before_retrying() {
    let resolver = Resolver::endpoints(vec![
        openai_endpoint(),
        openai_endpoint_with_key("sk-test-0002"),
    ]);
    let first: Responder = Arc::new(|events: &Events| {
        let events = events.clone();
        InboundResponse {
            status: 401,
            request_id: None,
            body: stream::once(async move {
                events.lock().unwrap().push("read".into());
                Ok(Bytes::from_static(b"{}"))
            })
            .boxed(),
        }
    });
    let transport = FakeTransport::new(vec![first, respond(200, ok_body())]);
    run(&resolver, &transport, &minimal_request(), None)
        .await
        .unwrap();
    let events = transport.events.lock().unwrap().clone();
    let read = events.iter().position(|e| e == "read").unwrap();
    let resend = events.iter().position(|e| e == "send1").unwrap();
    assert!(read < resend, "{events:?}");
}

#[tokio::test]
async fn cancels_oversized_error_bodies() {
    let dropped = Arc::new(AtomicBool::new(false));
    let flag = dropped.clone();
    let responder: Responder = Arc::new(move |_| {
        let guard = DropFlag(flag.clone());
        InboundResponse {
            status: 500,
            request_id: None,
            body: stream::unfold(guard, |guard| async move {
                Some((Ok(Bytes::from(vec![b'x'; 16 * 1024])), guard))
            })
            .boxed(),
        }
    });
    let resolver = Resolver::endpoints(vec![openai_endpoint()]);
    let transport = FakeTransport::new(vec![responder]);
    let error = run_err(&resolver, &transport, &minimal_request()).await;
    let d = details(&error);
    assert_eq!(d.body_truncated(), Some(true));
    assert_eq!(d.body().unwrap().len(), MAX_ERROR_BODY_BYTES);
    assert!(dropped.load(Ordering::SeqCst));
}

#[tokio::test]
async fn maps_mid_body_failures_to_network() {
    for status in [200u16, 500] {
        let responder: Responder = Arc::new(move |_| InboundResponse {
            status,
            request_id: None,
            body: stream::iter(vec![
                Ok(Bytes::from_static(b"{\"answers\":")),
                Err(BoxError::from("socket hang up")),
            ])
            .boxed(),
        });
        let resolver = Resolver::endpoints(vec![openai_endpoint()]);
        let transport = FakeTransport::new(vec![responder]);
        let error = run_err(&resolver, &transport, &minimal_request()).await;
        assert_eq!(kind_of(&error), "network", "HTTP {status}");
        assert_eq!(
            cause_of(&error).message(),
            "socket hang up",
            "HTTP {status}"
        );
    }
}

#[tokio::test]
async fn caps_success_bodies() {
    let resolver = Resolver::endpoints(vec![openai_endpoint()]);
    let huge = vec![b' '; MAX_RESPONSE_BYTES + 1];
    let responder: Responder = Arc::new(move |_| response(200, huge.clone()));
    let transport = FakeTransport::new(vec![responder]);
    let error = run_err(&resolver, &transport, &minimal_request()).await;
    assert_eq!(kind_of(&error), "invalidResponse");
    assert!(details(&error).message().contains("exceeded"));
}

#[tokio::test]
async fn surfaces_gate_hint_only_when_runtime_mentions_it() {
    let transport = FakeTransport::new(vec![respond(200, ok_body())]);
    let gated = Resolver::script(vec![Box::new(|| {
        Err(Error::with_message(
            ErrorKind::Rpc { code: -32603 },
            "Provider endpoint access is disabled. To enable this API, set COPILOT_ALLOW_GET_PROVIDER_ENDPOINT=true",
        ))
    })]);
    let error = run_err(&gated, &transport, &minimal_request()).await;
    assert_eq!(kind_of(&error), "endpointUnavailable");
    assert!(
        details(&error)
            .message()
            .contains("COPILOT_ALLOW_GET_PROVIDER_ENDPOINT=true")
    );

    let other = Resolver::script(vec![Box::new(|| {
        Err(Error::with_message(
            ErrorKind::Rpc { code: -32603 },
            "Session not found",
        ))
    })]);
    let error = run_err(&other, &transport, &minimal_request()).await;
    assert_eq!(kind_of(&error), "endpointUnavailable");
    let d = details(&error);
    assert!(!d.message().contains("COPILOT_ALLOW_GET_PROVIDER_ENDPOINT"));
    let cause = std::error::Error::source(d).expect("cause");
    assert!(cause.downcast_ref::<Error>().is_some());
    assert_eq!(transport.call_count(), 0);
}

#[tokio::test]
async fn cancellation_by_drop_aborts_the_request() {
    let dropped = Arc::new(AtomicBool::new(false));
    let flag = dropped.clone();
    let responder: Responder = Arc::new(move |_| {
        let guard = DropFlag(flag.clone());
        InboundResponse {
            status: 200,
            request_id: None,
            body: stream::unfold(guard, |guard| async move {
                std::future::pending::<()>().await;
                Some((Ok(Bytes::new()), guard))
            })
            .boxed(),
        }
    });
    let resolver = Resolver::endpoints(vec![openai_endpoint()]);
    let transport = FakeTransport::new(vec![responder]);
    let request = minimal_request();
    let outcome = tokio::time::timeout(
        std::time::Duration::from_millis(50),
        run(&resolver, &transport, &request, None),
    )
    .await;
    assert!(outcome.is_err(), "expected the call to be cancelled");
    assert!(dropped.load(Ordering::SeqCst));
}

// ============================================================================
// Redaction internals
// ============================================================================

#[test]
fn redacts_wire_encodings_case_insensitively() {
    let secret = "sk/ü é+key";
    let redactor = Redactor::new(collect_secrets(
        &openai_endpoint_with_key(secret),
        "https://api.openai.com/v1/decisions",
    ));
    let json_lower = ascii_json_escape(&json_escape(secret)).replace('/', "\\/");
    let json_upper = json_lower.replace("\\u00fc", "\\u00FC");
    let uri_lower = encode_uri_component(secret).to_ascii_lowercase();
    let plus = encode_uri_component(secret).replace("%20", "+");
    for text in [secret.to_owned(), json_lower, json_upper, uri_lower, plus] {
        assert_eq!(
            redactor.redact(&format!("a{text}b")),
            "a[REDACTED]b",
            "{text}"
        );
    }
}

#[test]
fn encode_uri_component_matches_javascript() {
    assert_eq!(
        encode_uri_component("a b/c?d=é!~*'()-_."),
        "a%20b%2Fc%3Fd%3D%C3%A9!~*'()-_."
    );
    assert_eq!(ascii_json_escape("😀"), "\\ud83d\\ude00");
    assert_eq!(ascii_json_escape("\u{7f}"), "\\u007f");
}

#[test]
fn header_values_are_trimmed_of_ows() {
    let ep = endpoint(&json!({
        "type": "openai",
        "baseUrl": "https://api.openai.com/v1",
        "headers": { "X-Extra": " \tvalue\t " }
    }));
    let headers = build_headers(&ep).unwrap_or_else(|e| panic!("{e}"));
    assert_eq!(headers.get("x-extra").map(String::as_str), Some("value"));
}

// ============================================================================
// Real HTTP
// ============================================================================

struct Hit {
    request_line: String,
    headers: HashMap<String, String>,
    body: Vec<u8>,
}

type Hits = Arc<Mutex<Vec<Hit>>>;
type Reply = (u16, Vec<(String, String)>, String);

/// Minimal HTTP/1.1 server: one request per connection, `Connection: close`.
async fn serve<F>(handler: F) -> (String, Hits, tokio::task::JoinHandle<()>)
where
    F: Fn(&str) -> Reply + Send + Sync + 'static,
{
    let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let port = listener.local_addr().unwrap().port();
    let hits: Hits = Arc::default();
    let recorded = hits.clone();
    let task = tokio::spawn(async move {
        loop {
            let Ok((mut socket, _)) = listener.accept().await else {
                return;
            };
            let mut buffer = Vec::new();
            let header_end = loop {
                let mut chunk = [0u8; 4096];
                let n = socket.read(&mut chunk).await.unwrap_or(0);
                if n == 0 {
                    break None;
                }
                buffer.extend_from_slice(&chunk[..n]);
                if let Some(pos) = buffer.windows(4).position(|w| w == b"\r\n\r\n") {
                    break Some(pos);
                }
            };
            let Some(header_end) = header_end else {
                continue;
            };
            let head = String::from_utf8_lossy(&buffer[..header_end]).into_owned();
            let mut lines = head.split("\r\n");
            let request_line = lines.next().unwrap_or_default().to_owned();
            let headers: HashMap<String, String> = lines
                .filter_map(|l| l.split_once(':'))
                .map(|(k, v)| (k.trim().to_ascii_lowercase(), v.trim().to_owned()))
                .collect();
            let length: usize = headers
                .get("content-length")
                .and_then(|v| v.parse().ok())
                .unwrap_or(0);
            let mut body = buffer[header_end + 4..].to_vec();
            while body.len() < length {
                let mut chunk = [0u8; 4096];
                let n = socket.read(&mut chunk).await.unwrap_or(0);
                if n == 0 {
                    break;
                }
                body.extend_from_slice(&chunk[..n]);
            }
            let path = request_line
                .split(' ')
                .nth(1)
                .unwrap_or_default()
                .to_owned();
            recorded.lock().unwrap().push(Hit {
                request_line,
                headers,
                body,
            });
            let (status, extra, payload) = handler(&path);
            let mut out = format!(
                "HTTP/1.1 {status} Status\r\ncontent-length: {}\r\nconnection: close\r\n",
                payload.len()
            );
            for (k, v) in extra {
                out.push_str(&format!("{k}: {v}\r\n"));
            }
            out.push_str("\r\n");
            out.push_str(&payload);
            let _ = socket.write_all(out.as_bytes()).await;
            let _ = socket.shutdown().await;
        }
    });
    (format!("http://127.0.0.1:{port}/v1"), hits, task)
}

#[tokio::test]
async fn round_trips_over_real_http() {
    let body = ok_body();
    let (base_url, hits, task) = serve(move |_| {
        (
            200,
            vec![
                ("content-type".into(), "application/json".into()),
                ("x-request-id".into(), "req_ok".into()),
            ],
            body.clone(),
        )
    })
    .await;
    let resolver = Resolver::endpoints(vec![endpoint(&json!({
        "type": "openai",
        "baseUrl": base_url,
        "apiKey": "sk-test-0001",
        "headers": { "X-Extra": "1" }
    }))]);
    let transport = ReqwestTransport::new(None);
    let result = run(&resolver, &transport, &minimal_request(), None)
        .await
        .unwrap_or_else(|e| panic!("{e:?}"));
    assert!(matches!(result.answers[0], DecisionsAnswer::Predicate(_)));
    {
        let hits = hits.lock().unwrap();
        assert_eq!(hits.len(), 1);
        assert!(hits[0].request_line.starts_with("POST /v1/decisions "));
        assert_eq!(
            hits[0].headers.get("authorization").map(String::as_str),
            Some("Bearer sk-test-0001")
        );
        assert_eq!(
            hits[0].headers.get("x-extra").map(String::as_str),
            Some("1")
        );
        assert_eq!(
            hits[0].headers.get("content-type").map(String::as_str),
            Some("application/json")
        );
        let sent: Value = serde_json::from_slice(&hits[0].body).unwrap();
        assert_eq!(sent, fixture("request.minimal.json"));
    }
    task.abort();
}

#[tokio::test]
async fn does_not_follow_redirects_over_real_http() {
    let body = ok_body();
    let (base_url, hits, task) = serve(move |path| {
        if path.ends_with("/decisions") {
            (
                307,
                vec![("location".into(), "/elsewhere".into())],
                String::new(),
            )
        } else {
            (200, vec![], body.clone())
        }
    })
    .await;
    let resolver = Resolver::endpoints(vec![endpoint(&json!({
        "type": "openai",
        "baseUrl": base_url,
        "apiKey": "sk-test-0001",
        "headers": {}
    }))]);
    let transport = ReqwestTransport::new(None);
    let error = run_err(&resolver, &transport, &minimal_request()).await;
    assert_eq!(kind_of(&error), "http");
    let d = details(&error);
    assert_eq!(d.status(), Some(307));
    assert!(d.message().contains("redirects are not followed"));
    let lines: Vec<String> = hits
        .lock()
        .unwrap()
        .iter()
        .map(|h| h.request_line.clone())
        .collect();
    assert_eq!(lines.len(), 1, "{lines:?}");
    assert!(lines[0].starts_with("POST /v1/decisions "));
    task.abort();
}

#[tokio::test]
async fn connection_failures_are_network_errors_over_real_http() {
    // Bind and drop a listener to get a port that refuses connections.
    let port = {
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        listener.local_addr().unwrap().port()
    };
    let resolver = Resolver::endpoints(vec![endpoint(&json!({
        "type": "openai",
        "baseUrl": format!("http://127.0.0.1:{port}/v1?api-key=sk-query-secret-0001"),
        "headers": {}
    }))]);
    let transport = ReqwestTransport::new(None);
    let error = run_err(&resolver, &transport, &minimal_request()).await;
    assert_eq!(kind_of(&error), "network");
    let cause = cause_of(&error);
    assert_eq!(cause.name(), "ConnectError");
    assert!(!format!("{error:?}").contains("sk-query-secret-0001"));
    assert!(!cause.message().contains("sk-query-secret-0001"));
}
