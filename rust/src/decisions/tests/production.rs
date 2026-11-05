#![cfg(test)]

use std::task::Poll;

use super::*;

mod native;
mod public_api;

fn production_vectors(group: &str) -> Vec<Value> {
    fixture("production-vectors.json")[group]
        .as_array()
        .expect("production vector group")
        .clone()
}

fn header<'a>(headers: &'a Value, name: &str) -> Option<&'a str> {
    headers.as_object()?.iter().find_map(|(key, value)| {
        key.eq_ignore_ascii_case(name)
            .then(|| value.as_str())
            .flatten()
    })
}

fn with_headers(mut response: InboundResponse, headers: &Value, now: i64) -> InboundResponse {
    response.request_id = header(headers, "x-request-id").map(str::to_owned);
    response.retry_after_ms = retry_after::parse(
        header(headers, "retry-after-ms"),
        header(headers, "retry-after"),
        now,
    );
    response
}

fn guarded_body(chunks: Vec<Result<Bytes, BoxError>>, dropped: Arc<AtomicBool>) -> BodyStream {
    stream::unfold(
        (chunks.into_iter(), DropFlag(dropped)),
        |(mut chunks, guard)| async move { chunks.next().map(|chunk| (chunk, (chunks, guard))) },
    )
    .boxed()
}

fn pending_body(dropped: Arc<AtomicBool>) -> BodyStream {
    stream::unfold(DropFlag(dropped), |guard| async move {
        std::future::pending::<()>().await;
        Some((Ok(Bytes::new()), guard))
    })
    .boxed()
}

#[tokio::test]
async fn vectors_metadata() {
    for vector in production_vectors("metadata") {
        let name = vector["name"].as_str().unwrap();
        let resolver = Resolver::endpoints(vec![openai_endpoint_with_key(
            vector["apiKey"].as_str().unwrap(),
        )]);
        let status = u16::try_from(vector["status"].as_u64().unwrap()).unwrap();
        let body = vector["body"].as_str().unwrap().to_owned();
        let headers = vector["headers"].clone();
        let responder: Responder =
            Arc::new(move |_| with_headers(response(status, body.clone()), &headers, 0));
        let transport = FakeTransport::new(vec![responder]);
        let error = run_err(&resolver, &transport, &minimal_request()).await;
        assert_eq!(kind_of(&error), "http", "{name}");
        let details = details(&error);
        assert_eq!(details.status(), Some(status), "{name}");
        assert_eq!(
            details.request_id(),
            vector["expectedRequestId"].as_str(),
            "{name}"
        );
        if let Some(expected) = vector.get("expectedErrorType") {
            assert_eq!(details.error_type(), expected.as_str(), "{name}");
        }
        if let Some(expected) = vector.get("expectedErrorCode") {
            assert_eq!(details.error_code(), expected.as_str(), "{name}");
        }
        assert!(
            !format!("{error:?}").contains(vector["apiKey"].as_str().unwrap()),
            "{name}"
        );
        assert_eq!(transport.call_count(), 1, "{name}");
        record_vector("production-vectors.json", "metadata", &vector);
    }
}

#[tokio::test]
async fn vectors_retry_after() {
    for vector in production_vectors("retryAfter") {
        let name = vector["name"].as_str().unwrap();
        let headers = vector["headers"].clone();
        let now = vector["nowEpochMs"].as_i64().unwrap();
        let expected = vector["expectedMs"].as_u64();
        assert_eq!(
            retry_after::parse(
                header(&headers, "retry-after-ms"),
                header(&headers, "retry-after"),
                now,
            ),
            expected,
            "{name}",
        );
        let responder: Responder =
            Arc::new(move |_| with_headers(response(429, "{}"), &headers, now));
        let resolver = Resolver::endpoints(vec![openai_endpoint()]);
        let transport = FakeTransport::new(vec![responder]);
        let error = run_err(&resolver, &transport, &minimal_request()).await;
        assert_eq!(kind_of(&error), "http", "{name}");
        assert_eq!(details(&error).status(), Some(429), "{name}");
        assert_eq!(details(&error).retry_after_ms(), expected, "{name}");
        assert_eq!(transport.call_count(), 1, "advisory only: {name}");
        assert_eq!(resolver.seen().len(), 1, "{name}");
        record_vector("production-vectors.json", "retryAfter", &vector);
    }
}

fn response_with_token(target: &str, token: &str) -> String {
    const MARKER: &str = "__DECISIONS_RAW_NUMBER_TOKEN__";
    let mut body = fixture("response.full.json");
    let pointer = format!("/{}", target.replace('.', "/"));
    *body.pointer_mut(&pointer).expect("fixture target") = json!(MARKER);
    let body = serde_json::to_string(&body).unwrap();
    let quoted_marker = serde_json::to_string(MARKER).unwrap();
    assert_eq!(body.matches(&quoted_marker).count(), 1);
    // Do not deserialize jsonValue before dispatch: huge numbers must reach
    // the public parser, and signed int64 literals must never pass through f64.
    body.replace(&quoted_marker, token)
}

fn result_integer(result: &DecisionsResult, target: &str) -> i64 {
    match target {
        "usage.input_tokens" => result.usage.input_tokens,
        "usage.output_tokens" => result.usage.output_tokens,
        "usage.total_tokens" => result.usage.total_tokens,
        "usage.input_tokens_details.cached_tokens" => {
            result.usage.input_tokens_details.cached_tokens
        }
        "usage.input_tokens_details.cache_write_tokens" => {
            result.usage.input_tokens_details.cache_write_tokens
        }
        "usage.output_tokens_details.reasoning_tokens" => {
            result.usage.output_tokens_details.reasoning_tokens
        }
        "answers.3.probabilities.0.value" => {
            let DecisionsAnswer::Score(answer) = &result.answers[3] else {
                panic!("expected score answer");
            };
            answer.probabilities[0].value
        }
        _ => panic!("unhandled integer target: {target}"),
    }
}

#[tokio::test]
async fn vectors_response_numbers() {
    for vector in production_vectors("responseNumbers") {
        let name = vector["name"].as_str().unwrap();
        let target = vector["target"].as_str().unwrap();
        let body = response_with_token(target, vector["jsonValue"].as_str().unwrap());
        let resolver = Resolver::endpoints(vec![openai_endpoint()]);
        let transport = FakeTransport::new(vec![respond(200, body)]);
        let result = run(&resolver, &transport, &minimal_request(), None).await;
        if let Some(expected) = vector["sdkErrors"]["rust"]
            .as_str()
            .or_else(|| vector["expectedError"].as_str())
        {
            let error = result.expect_err(name);
            assert_eq!(kind_of(&error), expected, "{name}");
        } else {
            let result = result.unwrap_or_else(|error| panic!("{name}: {error:?}"));
            if let Some(expected) = vector["expectedInteger"].as_str() {
                assert_eq!(
                    result_integer(&result, target).to_string(),
                    expected,
                    "{name}"
                );
            } else {
                assert_eq!(target, "answers.3.score", "unhandled numeric target");
                let DecisionsAnswer::Score(answer) = &result.answers[3] else {
                    panic!("expected score answer");
                };
                assert_eq!(
                    answer.score,
                    vector["expectedNumber"].as_f64().unwrap(),
                    "{name}"
                );
            }
        }
        assert_eq!(resolver.seen().len(), 1, "{name}");
        assert_eq!(transport.call_count(), 1, "{name}");
        record_vector("production-vectors.json", "responseNumbers", &vector);
    }
}

#[tokio::test]
async fn vectors_auth_body_failure() {
    for vector in production_vectors("authBodyFailure") {
        let name = vector["name"].as_str().unwrap();
        let status = u16::try_from(vector["status"].as_u64().unwrap()).unwrap();
        let dropped = Arc::new(AtomicBool::new(false));
        let flag = dropped.clone();
        let responder: Responder = Arc::new(move |_| InboundResponse {
            body: guarded_body(
                vec![
                    Ok(Bytes::from_static(b"{\"error\":")),
                    Err(BoxError::from("synthetic unreadable response")),
                ],
                flag.clone(),
            ),
            ..response(status, "")
        });
        let resolver = Resolver::endpoints(vec![
            openai_endpoint(),
            openai_endpoint_with_key("synthetic-refreshed-key"),
        ]);
        let transport = FakeTransport::new(vec![responder, respond(200, ok_body())]);
        let error = run_err(&resolver, &transport, &minimal_request()).await;
        assert_eq!(
            kind_of(&error),
            vector["expectedError"].as_str().unwrap(),
            "{name}"
        );
        assert_eq!(
            resolver.seen().len() as u64,
            vector["expectedResolves"].as_u64().unwrap(),
            "{name}"
        );
        assert_eq!(
            transport.call_count() as u64,
            vector["expectedSends"].as_u64().unwrap(),
            "{name}"
        );
        assert_eq!(
            dropped.load(Ordering::SeqCst),
            vector["expectedBodyClosed"].as_bool().unwrap(),
            "{name}"
        );
        assert_eq!(details(&error).status(), None, "{name}");
        record_vector("production-vectors.json", "authBodyFailure", &vector);
    }
}

struct StalledTransport {
    stage: String,
    status: u16,
    sends: AtomicUsize,
    dropped: Arc<AtomicBool>,
}

impl Transport for StalledTransport {
    fn send<'a>(
        &'a self,
        _url: &'a str,
        _headers: &'a IndexMap<String, String>,
        _body: Bytes,
    ) -> BoxFuture<'a, Result<InboundResponse, BoxError>> {
        Box::pin(async move {
            self.sends.fetch_add(1, Ordering::SeqCst);
            if self.stage == "headers" {
                let _guard = DropFlag(self.dropped.clone());
                return std::future::pending().await;
            }
            let body = if matches!(self.stage.as_str(), "successBody" | "errorBody") {
                pending_body(self.dropped.clone())
            } else {
                body_stream(b"{}".to_vec())
            };
            Ok(InboundResponse {
                body,
                ..response(self.status, "")
            })
        })
    }
}

#[tokio::test(start_paused = true)]
async fn vectors_deadline() {
    for vector in production_vectors("deadline") {
        let name = vector["name"].as_str().unwrap();
        let stage = vector["stage"].as_str().unwrap();
        let resolves = AtomicUsize::new(0);
        let endpoint_dropped = Arc::new(AtomicBool::new(false));
        let response_dropped = Arc::new(AtomicBool::new(false));
        let transport = StalledTransport {
            stage: stage.to_owned(),
            status: vector["status"].as_u64().unwrap_or(200) as u16,
            sends: AtomicUsize::new(0),
            dropped: response_dropped.clone(),
        };
        let resolve = |_| {
            let count = resolves.fetch_add(1, Ordering::SeqCst);
            let dropped = endpoint_dropped.clone();
            async move {
                if stage == "endpoint" || (stage == "refresh" && count == 1) {
                    let _guard = DropFlag(dropped);
                    return std::future::pending().await;
                }
                Ok(openai_endpoint())
            }
        };
        let timeout = Duration::from_millis(vector["timeoutMs"].as_u64().unwrap());
        let request = minimal_request();
        let mut call = Box::pin(execute(resolve, &transport, &request, None, Some(timeout)));
        assert!(
            matches!(futures_util::poll!(&mut call), Poll::Pending),
            "{name}"
        );
        tokio::time::advance(timeout - Duration::from_millis(1)).await;
        assert!(
            matches!(futures_util::poll!(&mut call), Poll::Pending),
            "{name}"
        );
        tokio::time::advance(Duration::from_millis(1)).await;
        let error = call.await.expect_err(name);
        assert_eq!(
            kind_of(&error),
            vector["expectedError"].as_str().unwrap(),
            "{name}"
        );
        assert_eq!(
            resolves.load(Ordering::SeqCst) as u64,
            vector["expectedResolves"].as_u64().unwrap(),
            "{name}"
        );
        assert_eq!(
            transport.sends.load(Ordering::SeqCst) as u64,
            vector["expectedSends"].as_u64().unwrap(),
            "{name}"
        );
        assert!(
            std::error::Error::source(details(&error)).is_none(),
            "{name}"
        );
        assert_eq!(details(&error).status(), None, "{name}");
        if matches!(stage, "endpoint" | "refresh") {
            assert!(endpoint_dropped.load(Ordering::SeqCst), "{name}");
        } else {
            assert!(response_dropped.load(Ordering::SeqCst), "{name}");
        }
        tokio::time::advance(Duration::from_secs(601)).await;
        assert_eq!(
            transport.sends.load(Ordering::SeqCst) as u64,
            vector["expectedSends"].as_u64().unwrap(),
            "{name}"
        );
        record_vector("production-vectors.json", "deadline", &vector);
    }
}
