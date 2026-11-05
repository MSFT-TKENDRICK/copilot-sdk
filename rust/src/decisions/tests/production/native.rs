#![cfg(test)]

use super::*;

#[test]
fn redaction_unions_original_overlaps_without_rewriting_replacements() {
    for (secrets, text, expected) in [
        (
            vec!["abcdefghij", "efghijklmn"],
            "abcdefghijklmn",
            "[REDACTED]",
        ),
        (vec!["abababab"], "abababababab", "[REDACTED]"),
        (vec!["aba"], "ababa", "[REDACTED]"),
        (vec!["key", "REDACTED"], "key key", "[REDACTED] [REDACTED]"),
        (vec!["key"], "keykey", "[REDACTED][REDACTED]"),
        (vec!["🔑🔑"], "🔑🔑🔑", "[REDACTED]"),
    ] {
        let redactor = Redactor::new(secrets.into_iter().map(str::to_owned).collect());
        assert_eq!(redactor.redact(text), expected);
    }
    let fallback = Redactor {
        patterns: vec![("K%2f\\u00ab".to_owned(), None)],
        max_secret_bytes: 10,
    };
    assert_eq!(fallback.redact("K%2F\\u00Ab"), REDACTED);
    assert_eq!(fallback.redact("k%2F\\u00Ab"), "k%2F\\u00Ab");
}

#[tokio::test]
async fn error_retention_never_imports_lookahead_after_shrinking() {
    let key = "synthetic-long-credential";
    let prefix = format!(
        "{key}{key}{}",
        "x".repeat(MAX_ERROR_BODY_BYTES - key.len() * 2)
    );
    let resolver = Resolver::endpoints(vec![openai_endpoint_with_key(key)]);
    let transport = FakeTransport::new(vec![respond(
        400,
        format!("{prefix}unrecognized-lookahead"),
    )]);
    let error = run_err(&resolver, &transport, &minimal_request()).await;
    assert_eq!(
        details(&error).body(),
        Some(
            format!(
                "{REDACTED}{REDACTED}{}",
                "x".repeat(MAX_ERROR_BODY_BYTES - key.len() * 2)
            )
            .as_str()
        )
    );
    assert_eq!(details(&error).body_truncated(), Some(true));

    let crossing = format!(
        "{key}{}{key}hidden",
        "x".repeat(MAX_ERROR_BODY_BYTES - key.len() - 3)
    );
    let transport = FakeTransport::new(vec![respond(400, crossing)]);
    let error = run_err(&resolver, &transport, &minimal_request()).await;
    assert_eq!(
        details(&error).body(),
        Some(
            format!(
                "{REDACTED}{}{REDACTED}",
                "x".repeat(MAX_ERROR_BODY_BYTES - key.len() - 3)
            )
            .as_str()
        )
    );
    assert_eq!(details(&error).body_truncated(), Some(true));
}

#[tokio::test]
async fn byte_budget_accounts_for_bom_lossy_decoding_and_split_utf8() {
    let resolver = Resolver::endpoints(vec![endpoint(&json!({
        "type": "openai", "baseUrl": "https://example.com", "headers": {}
    }))]);
    for (prefix, budget) in [
        (vec![], MAX_ERROR_BODY_BYTES),
        (b"\xef\xbb\xbf".to_vec(), MAX_ERROR_BODY_BYTES - 3),
    ] {
        for ch in ['é', '€', '😀'] {
            let mut body = prefix.clone();
            body.extend(vec![b'x'; budget - 1]);
            body.extend(ch.to_string().as_bytes());
            body.extend(b"hidden");
            let responder: Responder = Arc::new(move |_| response(500, body.clone()));
            let transport = FakeTransport::new(vec![responder]);
            let error = run_err(&resolver, &transport, &minimal_request()).await;
            let details = details(&error);
            assert_eq!(details.body_truncated(), Some(true));
            assert_eq!(details.body(), Some("x".repeat(budget - 1).as_str()));
        }
    }
    let mut body = vec![0xff];
    body.extend(vec![b'x'; MAX_ERROR_BODY_BYTES - 1]);
    body.extend(b"hidden");
    let responder: Responder = Arc::new(move |_| response(500, body.clone()));
    let transport = FakeTransport::new(vec![responder]);
    let error = run_err(&resolver, &transport, &minimal_request()).await;
    let retained = details(&error).body().unwrap();
    assert!(retained.starts_with('\u{fffd}'));
    assert_eq!(retained.len(), MAX_ERROR_BODY_BYTES);
    assert!(!retained.contains("hidden"));
}

#[tokio::test]
async fn every_actual_credential_is_protected_including_shadowed_headers() {
    let endpoint = endpoint(&json!({
        "type": "openai", "baseUrl": "https://example.com",
        "apiKey": "k",
        "headers": {"Authorization": "Bearer q", "Api-Key": "r", "X-Session": "u"},
        "sessionToken": {"header": "x-session", "token": "t"}
    }));
    let resolver = Resolver::endpoints(vec![endpoint]);
    let transport = FakeTransport::new(vec![respond(400, "k q r t u Bearer q")]);
    let error = run_err(&resolver, &transport, &minimal_request()).await;
    assert_eq!(
        details(&error).body(),
        Some([REDACTED; 6].join(" ").as_str())
    );
}

#[tokio::test]
async fn replay_diagnostics_protect_credentials_from_both_attempts() {
    let resolver = Resolver::endpoints(vec![
        openai_endpoint_with_key("synthetic-old-key"),
        openai_endpoint_with_key("synthetic-new-key"),
    ]);
    let transport = FakeTransport::new(vec![
        respond(401, "{}"),
        respond_with_id(
            400,
            "synthetic-old-key synthetic-new-key",
            "synthetic-old-key",
        ),
    ]);
    let error = run_err(&resolver, &transport, &minimal_request()).await;
    assert_eq!(kind_of(&error), "http");
    assert_eq!(details(&error).body(), Some("[REDACTED] [REDACTED]"));
    assert_eq!(details(&error).request_id(), Some(REDACTED));
    assert_eq!(transport.call_count(), 2);
}

struct ReplayNetworkFailure {
    sends: AtomicUsize,
}

impl Transport for ReplayNetworkFailure {
    fn send<'a>(
        &'a self,
        _url: &'a str,
        _headers: &'a IndexMap<String, String>,
        _body: Bytes,
    ) -> BoxFuture<'a, Result<InboundResponse, BoxError>> {
        Box::pin(async move {
            if self.sends.fetch_add(1, Ordering::SeqCst) == 0 {
                Ok(response(401, "{}"))
            } else {
                Err(BoxError::from(std::io::Error::other(
                    "synthetic-old-key synthetic-new-key",
                )))
            }
        })
    }
}

#[tokio::test]
async fn replay_network_cause_protects_credentials_from_both_attempts() {
    let resolver = Resolver::endpoints(vec![
        openai_endpoint_with_key("synthetic-old-key"),
        openai_endpoint_with_key("synthetic-new-key"),
    ]);
    let transport = ReplayNetworkFailure {
        sends: AtomicUsize::new(0),
    };
    let error = run_err(&resolver, &transport, &minimal_request()).await;
    assert_eq!(kind_of(&error), "network");
    assert_eq!(cause_of(&error).message(), "[REDACTED] [REDACTED]");
    let debug = format!("{error:?}");
    assert!(!debug.contains("synthetic-old-key"));
    assert!(!debug.contains("synthetic-new-key"));
    assert_eq!(transport.sends.load(Ordering::SeqCst), 2);
    assert_eq!(resolver.seen().len(), 2);
}

#[tokio::test]
async fn raw_and_decoded_query_representations_are_protected_in_all_diagnostics() {
    for (raw, echo, decoded) in [
        (
            "%FFcredential-0001",
            "%ffcredential-0001",
            "\u{fffd}credential-0001",
        ),
        (
            "a+very%2Flong+query",
            "a+very%2flong+query",
            "a very/long query",
        ),
        (
            "%C3%89credential-0001",
            "%c3%89credential-0001",
            "Écredential-0001",
        ),
    ] {
        let endpoint = endpoint(&json!({
            "type": "openai", "baseUrl": format!("https://example.com?empty&&sig={raw}&bare"),
            "headers": {}
        }));
        let resolver = Resolver::endpoints(vec![endpoint]);
        let body = json!({"error": {
            "type": echo, "code": echo, "param": decoded,
            "message": format!("{echo} {decoded}"),
        }})
        .to_string();
        let transport = FakeTransport::new(vec![respond_with_id(400, body, echo)]);
        let error = run_err(&resolver, &transport, &minimal_request()).await;
        assert_eq!(kind_of(&error), "http");
        assert_eq!(details(&error).request_id(), Some(REDACTED));
        assert_eq!(details(&error).error_type(), Some(REDACTED));
        assert_eq!(details(&error).error_code(), Some(REDACTED));
        assert_eq!(details(&error).error_param(), Some(REDACTED));
        let debug = format!("{error:?}");
        for secret in [raw, echo, decoded] {
            assert!(!debug.contains(secret));
        }
        let transport = FailingTransport(Box::new(move || {
            Box::new(std::io::Error::other(format!("{echo} {decoded}")))
        }));
        let error = run_err(&resolver, &transport, &minimal_request()).await;
        assert_eq!(kind_of(&error), "network");
        assert_eq!(cause_of(&error).message(), "[REDACTED] [REDACTED]");
    }
    let endpoint = endpoint(&json!({
        "type": "openai", "baseUrl": "https://example.com?sig=%FFabcdef", "headers": {}
    }));
    let plan = plan_attempt(&endpoint, "m").unwrap();
    assert_eq!(
        plan.redactor.redact("%ffabcdef"),
        "%ffabcdef",
        "decoded candidate is only seven scalars"
    );
}

#[test]
fn only_noncredential_candidates_use_the_scalar_length_heuristic() {
    let endpoint = endpoint(&json!({
        "type": "openai",
        "baseUrl": "https://example.com?short=abcdefg&long=abcdefgh",
        "headers": {"X-Short": "1234567", "X-Long": "12345678", "X-Unicode": "🔑🔑🔑🔑abc"},
        "apiKey": " \tZ\t "
    }));
    let plan = plan_attempt(&endpoint, "m").unwrap();
    assert_eq!(
        plan.redactor
            .redact("1234567 12345678 abcdefg abcdefgh 🔑🔑🔑🔑abc Z"),
        "1234567 [REDACTED] abcdefg [REDACTED] 🔑🔑🔑🔑abc [REDACTED]",
    );
}

#[tokio::test]
async fn all_provider_metadata_is_redacted_and_bounded() {
    let body = json!({"error": {
        "message": "Z ".repeat(8_000),
        "type": "Z".repeat(5_000),
        "code": "Z".repeat(5_000),
        "param": "Z".repeat(5_000),
    }})
    .to_string();
    assert!(body.len() < MAX_ERROR_BODY_BYTES);
    let resolver = Resolver::endpoints(vec![openai_endpoint_with_key("Z")]);
    let transport = FakeTransport::new(vec![respond_with_id(
        400,
        body,
        &format!("{}Z", "q".repeat(MAX_ERROR_BODY_BYTES + 40)),
    )]);
    let error = run_err(&resolver, &transport, &minimal_request()).await;
    let details = details(&error);
    assert_eq!(details.body_truncated(), Some(false));
    for value in [
        Some(details.message()),
        details.body(),
        details.error_type(),
        details.error_code(),
        details.error_param(),
        details.request_id(),
    ] {
        let value = value.unwrap();
        assert!(value.len() <= MAX_ERROR_BODY_BYTES);
        assert!(!value.contains('Z'));
    }
    assert!(details.message().contains(REDACTED));
    assert!(details.error_type().unwrap().contains(REDACTED));
    assert!(!format!("{error:?}").contains('Z'));

    let transport = FailingTransport(Box::new(|| {
        Box::new(std::io::Error::new(
            std::io::ErrorKind::TimedOut,
            "Z ".repeat(70_000),
        ))
    }));
    let error = run_err(&resolver, &transport, &minimal_request()).await;
    let cause = cause_of(&error);
    assert!(cause.message().len() <= MAX_ERROR_BODY_BYTES);
    assert!(!cause.message().contains('Z'));
    assert!(std::error::Error::source(cause).is_none());
}

#[tokio::test]
async fn cause_name_and_code_are_protected_too() {
    let resolver = Resolver::endpoints(vec![endpoint(&json!({
        "type": "openai", "baseUrl": "https://example.com", "apiKey": "IoError", "headers": {},
        "sessionToken": {"header": "X-Session", "token": "TimedOut"}
    }))]);
    let transport = FailingTransport(Box::new(|| {
        Box::new(std::io::Error::new(
            std::io::ErrorKind::TimedOut,
            "synthetic failure",
        ))
    }));
    let error = run_err(&resolver, &transport, &minimal_request()).await;
    let cause = cause_of(&error);
    assert_eq!(cause.name(), REDACTED);
    assert_eq!(cause.code(), Some(REDACTED));
}

#[tokio::test]
async fn endpoint_and_preflight_errors_do_not_retain_unrecognized_secrets() {
    let secret = "synthetic-unknown-rpc-credential";
    for refresh in [false, true] {
        let mut steps: Vec<ResolveStep> = Vec::new();
        if refresh {
            steps.push(Box::new(|| Ok(openai_endpoint())));
        }
        steps.push(Box::new(move || {
            Err(Error::with_message(
                ErrorKind::Rpc { code: -32000 },
                format!("Provider URL contains {secret}"),
            ))
        }));
        let resolver = Resolver::script(steps);
        let transport = FakeTransport::new(vec![respond(401, "{}")]);
        let error = run_err(&resolver, &transport, &minimal_request()).await;
        assert_eq!(kind_of(&error), "endpointUnavailable");
        assert!(!format!("{error:?}").contains(secret));
        assert!(std::error::Error::source(details(&error)).is_none());
        assert_eq!(transport.call_count(), usize::from(refresh));
    }
    let ep = endpoint(&json!({
        "type": "openai", "baseUrl": "https://example.com", "headers": {},
        "sessionToken": {"header": "X-Session", "token": "synthetic-token", "model": secret}
    }));
    let error = plan_attempt(&ep, "synthetic-private-request-model")
        .err()
        .unwrap();
    assert_eq!(error.kind(), DecisionsErrorKind::ModelMismatch);
    assert!(!format!("{error:?}").contains(secret));
    assert!(!format!("{error:?}").contains("synthetic-private-request-model"));
}

#[test]
fn strict_url_authorities_ports_and_encoded_paths() {
    for (url, expected) in [
        ("http://[::1]/v1", "http://[::1]/v1/decisions"),
        ("https://[::1]:443/v1", "https://[::1]/v1/decisions"),
        ("http://localhost:0/v1", "http://localhost:0/v1/decisions"),
        (
            "http://LOCALHOST:80/a//b/../v1?x=%2F&x=2#ignored",
            "http://localhost/a//v1/decisions?x=%2F&x=2",
        ),
        (
            "https://xn--bcher-kva.example/v1?tag=%C3%A9",
            "https://xn--bcher-kva.example/v1/decisions?tag=%C3%A9",
        ),
    ] {
        assert_eq!(
            build_url(&ProviderEndpointType::Openai, url).unwrap(),
            expected
        );
    }
    for url in [
        "https:///example.com",
        "http://",
        "https://?q=1",
        "http://example.com:",
        "http://example.com:-1",
        "http://example.com:+1",
        "http://example.com:65536",
        "http://example.com:1:2",
        "http://[::1]suffix/v1",
        "http://[::g]/v1",
        "http://exa..mple/v1",
        "http://-host.example/v1",
        "http://example.com./%1",
        "http://example.com/%zz",
        "http://example.com/#%",
        "http://example.com/\u{7f}",
        "http://example.com/v1 ",
        "http://example.com\\v1",
        "https://example.com/é",
    ] {
        let error = build_url(&ProviderEndpointType::Openai, url).expect_err(url);
        assert_eq!(error.kind(), DecisionsErrorKind::InvalidEndpoint);
        assert!(!error.message().contains(url));
    }
}

#[test]
fn headers_validate_all_credentials_before_encoding_even_when_shadowed() {
    for byte in (0..=31u8).filter(|byte| *byte != b'\t').chain([127]) {
        let ep = endpoint(&json!({
            "type": "openai", "baseUrl": "https://example.com",
            "apiKey": format!("synthetic{}key", char::from(byte)),
            "headers": {"Authorization": "Bearer synthetic-override"}
        }));
        assert_eq!(
            plan_attempt(&ep, "m").err().unwrap().kind(),
            DecisionsErrorKind::InvalidEndpoint
        );
    }
    let ep = endpoint(&json!({
        "type": "openai", "baseUrl": "https://example.com",
        "headers": {"X-Scalar": " \tü🔑\u{a0}\t "}
    }));
    let plan = plan_attempt(&ep, "m").unwrap();
    assert_eq!(plan.headers["x-scalar"], "ü🔑\u{a0}");
    assert!(
        serde_json::from_str::<ProviderEndpoint>(
            r#"{"type":"openai","baseUrl":"https://example.com","headers":{"X-Bad":"\ud800"}}"#
        )
        .is_err()
    );
}

#[tokio::test]
async fn numeric_guards_preserve_exact_int64_and_reject_imprecise_encodings() {
    let targets = [
        "usage.input_tokens",
        "usage.output_tokens",
        "usage.total_tokens",
        "usage.input_tokens_details.cached_tokens",
        "usage.input_tokens_details.cache_write_tokens",
        "usage.output_tokens_details.reasoning_tokens",
        "answers.3.probabilities.0.value",
    ];
    for target in targets {
        for (token, expected) in [
            ("-9223372036854775808", Some(i64::MIN)),
            ("9223372036854775807", Some(i64::MAX)),
            ("-9007199254740991.0", Some(-MAX_SAFE_INTEGER)),
            ("9007199254740991.0", Some(MAX_SAFE_INTEGER)),
            ("-3e0", Some(-3)),
            ("30e-1", Some(3)),
            ("3.0000000000000000000000000000", Some(3)),
            ("0e999999999999999999999999", Some(0)),
            ("1.0000000000000000000000000001", None),
            ("0.9999999999999999999999999999", None),
            ("0.5", None),
            ("true", None),
            ("null", None),
            ("\"1\"", None),
            ("-9223372036854775809", None),
            ("9223372036854775808", None),
            ("9007199254740992.0", None),
            ("-9007199254740992.0", None),
            ("9.223372036854776e18", None),
            ("1e400", None),
        ] {
            let resolver = Resolver::endpoints(vec![openai_endpoint()]);
            let transport =
                FakeTransport::new(vec![respond(200, response_with_token(target, token))]);
            let result = run(&resolver, &transport, &minimal_request(), None).await;
            if let Some(expected) = expected {
                assert_eq!(
                    result_integer(&result.unwrap(), target),
                    expected,
                    "{target}: {token}"
                );
            } else {
                assert_eq!(
                    kind_of(&result.unwrap_err()),
                    "invalidResponse",
                    "{target}: {token}"
                );
            }
        }
    }
}

#[tokio::test]
async fn known_floats_are_finite_numbers_and_probability_domains_are_checked() {
    for target in [
        "answers.0.probability",
        "answers.1.confidence",
        "answers.3.score",
        "answers.3.probabilities.0.probability",
    ] {
        for token in ["1e400", "NaN", "Infinity", "null", "true", "\"0.5\""] {
            let resolver = Resolver::endpoints(vec![openai_endpoint()]);
            let transport =
                FakeTransport::new(vec![respond(200, response_with_token(target, token))]);
            assert_eq!(
                kind_of(&run_err(&resolver, &transport, &minimal_request()).await),
                "invalidResponse"
            );
        }
    }
    for token in ["-0.01", "1.01"] {
        let resolver = Resolver::endpoints(vec![openai_endpoint()]);
        let transport = FakeTransport::new(vec![respond(
            200,
            response_with_token("answers.0.probability", token),
        )]);
        assert_eq!(
            kind_of(&run_err(&resolver, &transport, &minimal_request()).await),
            "invalidResponse"
        );
    }
    for target in ["usage.input_tokens_details", "usage.output_tokens_details"] {
        for token in ["null", "[]", "\"missing\""] {
            let resolver = Resolver::endpoints(vec![openai_endpoint()]);
            let transport =
                FakeTransport::new(vec![respond(200, response_with_token(target, token))]);
            assert_eq!(
                kind_of(&run_err(&resolver, &transport, &minimal_request()).await),
                "invalidResponse"
            );
        }
    }
}

#[tokio::test]
async fn response_streams_drop_on_every_terminal_path() {
    for (status, chunks, expected) in [
        (200, vec![Ok(Bytes::from(ok_body()))], None),
        (
            200,
            vec![Ok(Bytes::from_static(b"not json"))],
            Some("invalidResponse"),
        ),
        (
            200,
            vec![Ok(Bytes::from_static(b"{}"))],
            Some("invalidResponse"),
        ),
        (
            200,
            vec![Ok(Bytes::from(vec![b' '; MAX_RESPONSE_BYTES + 1]))],
            Some("invalidResponse"),
        ),
        (
            200,
            vec![Err(BoxError::from("synthetic body read failure"))],
            Some("network"),
        ),
        (
            500,
            vec![Err(BoxError::from("synthetic body read failure"))],
            Some("network"),
        ),
        (500, vec![Ok(Bytes::from_static(b"{}"))], Some("http")),
    ] {
        let dropped = Arc::new(AtomicBool::new(false));
        let body = Mutex::new(Some(guarded_body(chunks, dropped.clone())));
        let responder: Responder = Arc::new(move |_| InboundResponse {
            body: body.lock().unwrap().take().unwrap(),
            ..response(status, "")
        });
        let resolver = Resolver::endpoints(vec![openai_endpoint()]);
        let transport = FakeTransport::new(vec![responder]);
        let result = run(&resolver, &transport, &minimal_request(), None).await;
        if let Some(expected) = expected {
            assert_eq!(kind_of(&result.unwrap_err()), expected);
        } else {
            assert!(result.is_ok());
        }
        assert!(
            dropped.load(Ordering::SeqCst),
            "status {status}, expected {expected:?}"
        );
    }
}

#[tokio::test]
async fn auth_response_is_closed_before_refresh_and_no_other_status_retries() {
    for status in [401, 403] {
        let dropped = Arc::new(AtomicBool::new(false));
        let flag = dropped.clone();
        let resolver = Resolver::script(vec![
            Box::new(|| Ok(openai_endpoint())),
            Box::new(move || {
                assert!(flag.load(Ordering::SeqCst));
                Ok(openai_endpoint_with_key("synthetic-refreshed-key"))
            }),
        ]);
        let first: Responder = Arc::new(move |_| InboundResponse {
            body: guarded_body(vec![Ok(Bytes::from_static(b"{}"))], dropped.clone()),
            ..response(status, "")
        });
        let transport = FakeTransport::new(vec![first, respond(200, ok_body())]);
        assert!(
            run(&resolver, &transport, &minimal_request(), None)
                .await
                .is_ok()
        );
        assert_eq!(transport.call_count(), 2);
    }
    for status in [301, 400, 408, 429, 500, 503] {
        let resolver = Resolver::endpoints(vec![openai_endpoint()]);
        let transport = FakeTransport::new(vec![respond(status, "{}")]);
        assert_eq!(
            kind_of(&run_err(&resolver, &transport, &minimal_request()).await),
            "http"
        );
        assert_eq!(transport.call_count(), 1);
        assert_eq!(resolver.seen().len(), 1);
    }
}

#[tokio::test]
async fn typed_question_hygiene_and_bom_success_preserve_construction_compatibility() {
    let options = DecisionsOptions {
        endpoint_model_id: None,
        http_client: None,
    };
    assert!(options.endpoint_model_id.is_none());
    let public_result = DecisionsResult {
        answers: vec![],
        model: "m".to_owned(),
        usage: DecisionsUsage::default(),
    };
    assert_eq!(public_result.usage.input_tokens, 0);
    let resolver = Resolver::endpoints(vec![openai_endpoint()]);
    let transport = FakeTransport::new(vec![respond(200, format!("\u{feff}{}", ok_body()))]);
    let result = run(&resolver, &transport, &full_request(), None)
        .await
        .unwrap();
    assert_eq!(result.answers.len(), 6);
    for question in [
        DecisionsQuestion::Predicate(DecisionsPredicateQuestion {
            name: None,
            instructions: vec![DecisionsQuestionPart::Json(DecisionsInputJson {
                data: json!({}),
            })]
            .into(),
        }),
        full_request().questions.remove(2),
        DecisionsQuestion::Score(DecisionsScoreQuestion {
            name: None,
            instructions: vec![DecisionsQuestionPart::Json(DecisionsInputJson {
                data: json!({}),
            })]
            .into(),
            levels: vec![
                DecisionsScoreLevel {
                    label: "low".into(),
                    description: None,
                },
                DecisionsScoreLevel {
                    label: "high".into(),
                    description: None,
                },
            ],
        }),
    ] {
        let resolver = Resolver::endpoints(vec![openai_endpoint()]);
        let transport = FakeTransport::new(vec![respond(200, ok_body())]);
        let mut request = minimal_request();
        request.questions = vec![question];
        let result = run(&resolver, &transport, &request, None).await.unwrap();
        assert_eq!(result.answers.len(), 6);
        assert_eq!(resolver.seen(), vec![None]);
        let calls = transport.calls.lock().unwrap();
        assert_eq!(calls.len(), 1);
        let body: Value = serde_json::from_slice(&calls[0].body).unwrap();
        assert_eq!(body, serde_json::to_value(&request).unwrap());
        let parts = body["questions"][0]["instructions"].as_array().unwrap();
        assert!(parts.iter().any(|part| part["type"] == "input_json"));
        assert!(
            parts
                .iter()
                .all(|part| part.get("cache_breakpoint").is_none())
        );
    }
}

#[test]
fn retry_hint_boundaries_use_exact_decimal_and_calendar_arithmetic() {
    let now = 1_445_412_420_000;
    for (ms, seconds, expected) in [
        (
            Some("9007199254740991.0"),
            None,
            Some(MAX_SAFE_INTEGER as u64),
        ),
        (
            Some("9007199254740990.1"),
            None,
            Some(MAX_SAFE_INTEGER as u64),
        ),
        (Some("9007199254740991.1"), Some("1"), Some(1_000)),
        (
            None,
            Some("9007199254740.991"),
            Some(MAX_SAFE_INTEGER as u64),
        ),
        (None, Some("9007199254740.9911"), None),
        (Some("0.0000000000000000000000001"), None, Some(1)),
        (Some(".5"), None, None),
        (Some("5."), None, None),
        (None, Some("2, 3"), None),
        (None, Some("1e2"), None),
        (None, Some("Wed, 21 Oct 2015 07:28:00 GMT"), Some(60_000)),
        (None, Some("Thu, 21 Oct 2015 07:28:00 GMT"), None),
        (None, Some("Wed, 21 Oct 2015 24:28:00 GMT"), None),
        (None, Some("Wed, 21 Oct 2015 07:28:60 GMT"), None),
        (None, Some("Sunday, 06-Nov-94 08:49:37 GMT"), None),
        (
            None,
            Some("Mon, 29 Feb 2016 00:00:00 GMT"),
            Some(11_291_580_000),
        ),
        (None, Some("Wed, 29 Feb 2017 00:00:00 GMT"), None),
    ] {
        assert_eq!(
            retry_after::parse(ms, seconds, now),
            expected,
            "{ms:?} {seconds:?}"
        );
    }
}

struct TimelineTransport {
    sends: AtomicUsize,
    dropped: Arc<AtomicBool>,
}

impl Transport for TimelineTransport {
    fn send<'a>(
        &'a self,
        _url: &'a str,
        _headers: &'a IndexMap<String, String>,
        _body: Bytes,
    ) -> BoxFuture<'a, Result<InboundResponse, BoxError>> {
        Box::pin(async move {
            let index = self.sends.fetch_add(1, Ordering::SeqCst);
            tokio::time::sleep(Duration::from_millis(50)).await;
            if index == 0 {
                return Ok(response(401, "{}"));
            }
            let guard = DropFlag(self.dropped.clone());
            Ok(InboundResponse {
                body: stream::once(async move {
                    let _guard = guard;
                    tokio::time::sleep(Duration::from_millis(100)).await;
                    Ok(Bytes::from(ok_body()))
                })
                .boxed(),
                ..response(200, "")
            })
        })
    }
}

#[tokio::test(start_paused = true)]
async fn deadline_is_total_not_reset_after_auth_refresh() {
    let resolves = AtomicUsize::new(0);
    let resolve = |_| {
        let count = resolves.fetch_add(1, Ordering::SeqCst);
        async move {
            tokio::time::sleep(Duration::from_millis(if count == 0 { 100 } else { 25 })).await;
            Ok(openai_endpoint_with_key(if count == 0 {
                "synthetic-old-key"
            } else {
                "synthetic-new-key"
            }))
        }
    };
    let dropped = Arc::new(AtomicBool::new(false));
    let transport = TimelineTransport {
        sends: AtomicUsize::new(0),
        dropped: dropped.clone(),
    };
    let start = tokio::time::Instant::now();
    let error = execute(
        resolve,
        &transport,
        &minimal_request(),
        None,
        Some(Duration::from_millis(250)),
    )
    .await
    .unwrap_err();
    assert_eq!(kind_of(&error), "timeout");
    assert_eq!(
        tokio::time::Instant::now() - start,
        Duration::from_millis(250)
    );
    assert_eq!(resolves.load(Ordering::SeqCst), 2);
    assert_eq!(transport.sends.load(Ordering::SeqCst), 2);
    assert!(dropped.load(Ordering::SeqCst));
}

#[tokio::test(start_paused = true)]
async fn timeout_default_disabled_zero_and_invalid_model_order() {
    assert_eq!(DEFAULT_TIMEOUT, Duration::from_secs(600));
    let transport = FakeTransport::new(vec![respond(200, ok_body())]);
    let slow_resolve = |_| async {
        tokio::time::sleep(Duration::from_secs(601)).await;
        Ok(openai_endpoint())
    };
    let start = tokio::time::Instant::now();
    let error = execute(
        slow_resolve,
        &transport,
        &minimal_request(),
        None,
        Some(DEFAULT_TIMEOUT),
    )
    .await
    .unwrap_err();
    assert_eq!(kind_of(&error), "timeout");
    assert_eq!(tokio::time::Instant::now() - start, DEFAULT_TIMEOUT);
    assert_eq!(transport.call_count(), 0);
    assert!(
        execute(slow_resolve, &transport, &minimal_request(), None, None)
            .await
            .is_ok()
    );
    assert_eq!(transport.call_count(), 1);

    let resolver = Resolver::endpoints(vec![openai_endpoint()]);
    let transport = FakeTransport::new(vec![respond(200, ok_body())]);
    let mut request = minimal_request();
    let error = execute(
        |model| resolver.resolve(model),
        &transport,
        &request,
        None,
        Some(Duration::ZERO),
    )
    .await
    .unwrap_err();
    assert_eq!(kind_of(&error), "timeout");
    request.model.clear();
    let error = execute(
        |model| resolver.resolve(model),
        &transport,
        &request,
        None,
        Some(Duration::ZERO),
    )
    .await
    .unwrap_err();
    assert_eq!(error.kind(), &ErrorKind::InvalidConfig);
    assert!(resolver.seen().is_empty());
    assert_eq!(transport.call_count(), 0);
}

#[tokio::test(start_paused = true)]
async fn a_late_endpoint_reply_cannot_dispatch_http() {
    let (sender, receiver) = tokio::sync::oneshot::channel::<ProviderEndpoint>();
    let receiver = Mutex::new(Some(receiver));
    let dropped = Arc::new(AtomicBool::new(false));
    let resolve = |_| {
        let receiver = receiver.lock().unwrap().take().unwrap();
        let guard = DropFlag(dropped.clone());
        async move {
            let _guard = guard;
            Ok(receiver.await.unwrap())
        }
    };
    let transport = FakeTransport::new(vec![respond(200, ok_body())]);
    let error = execute(
        resolve,
        &transport,
        &minimal_request(),
        None,
        Some(Duration::from_millis(10)),
    )
    .await
    .unwrap_err();
    assert_eq!(kind_of(&error), "timeout");
    assert!(dropped.load(Ordering::SeqCst));
    assert!(sender.send(openai_endpoint()).is_err());
    tokio::time::advance(Duration::from_secs(1)).await;
    assert_eq!(transport.call_count(), 0);
}

#[tokio::test(start_paused = true)]
async fn caller_future_drop_is_native_at_every_stage() {
    for stage in ["endpoint", "headers", "successBody", "errorBody", "refresh"] {
        let resolves = AtomicUsize::new(0);
        let dropped = Arc::new(AtomicBool::new(false));
        let transport = StalledTransport {
            stage: stage.to_owned(),
            status: if matches!(stage, "refresh" | "errorBody") {
                401
            } else {
                200
            },
            sends: AtomicUsize::new(0),
            dropped: dropped.clone(),
        };
        let resolve = |_| {
            let count = resolves.fetch_add(1, Ordering::SeqCst);
            let dropped = dropped.clone();
            async move {
                if stage == "endpoint" || (stage == "refresh" && count == 1) {
                    let _guard = DropFlag(dropped);
                    return std::future::pending().await;
                }
                Ok(openai_endpoint())
            }
        };
        let request = minimal_request();
        let mut call = Box::pin(execute(resolve, &transport, &request, None, None));
        assert!(matches!(futures_util::poll!(&mut call), Poll::Pending));
        drop(call);
        assert!(dropped.load(Ordering::SeqCst), "{stage}");
        let sends = transport.sends.load(Ordering::SeqCst);
        tokio::time::advance(Duration::from_secs(601)).await;
        assert_eq!(transport.sends.load(Ordering::SeqCst), sends);
    }
    let resolver = Resolver::endpoints(vec![openai_endpoint()]);
    let transport = FakeTransport::new(vec![respond(200, ok_body())]);
    let request = minimal_request();
    let token = tokio_util::sync::CancellationToken::new();
    token.cancel();
    let cancelled = tokio::select! {
        biased;
        _ = token.cancelled() => true,
        _ = execute(|model| resolver.resolve(model), &transport, &request, None, Some(Duration::ZERO)) => false,
    };
    assert!(cancelled);
    assert!(resolver.seen().is_empty());
    assert_eq!(transport.call_count(), 0);
}

#[tokio::test(start_paused = true)]
async fn always_ready_empty_chunks_cannot_starve_the_deadline() {
    for status in [200, 401] {
        let dropped = Arc::new(AtomicBool::new(false));
        let flag = dropped.clone();
        let responder: Responder = Arc::new(move |_| InboundResponse {
            body: stream::unfold(DropFlag(flag.clone()), |guard| async move {
                Some((Ok(Bytes::new()), guard))
            })
            .boxed(),
            ..response(status, "")
        });
        let resolver = Resolver::endpoints(vec![openai_endpoint()]);
        let transport = FakeTransport::new(vec![responder]);
        let request = minimal_request();
        let mut call = Box::pin(execute(
            |model| resolver.resolve(model),
            &transport,
            &request,
            None,
            Some(Duration::from_millis(10)),
        ));
        assert!(matches!(futures_util::poll!(&mut call), Poll::Pending));
        tokio::time::advance(Duration::from_millis(10)).await;
        assert_eq!(kind_of(&call.await.unwrap_err()), "timeout");
        assert!(dropped.load(Ordering::SeqCst));
        assert_eq!(resolver.seen().len(), 1);
        assert_eq!(transport.call_count(), 1);
    }
}

#[tokio::test]
async fn retry_hints_and_auth_refresh_work_over_real_http() {
    let count = Arc::new(AtomicUsize::new(0));
    let seen = count.clone();
    let body = ok_body();
    let (url, hits, task) = serve(move |_| {
        let index = seen.fetch_add(1, Ordering::SeqCst);
        match index {
            0 => (401, vec![], "{}".into()),
            1 => (200, vec![], body.clone()),
            _ => (
                429,
                vec![
                    ("Retry-After-Ms".into(), "1500.1".into()),
                    ("Retry-After".into(), "9".into()),
                    ("X-Request-Id".into(), "req:synthetic-new-key".into()),
                ],
                "{}".into(),
            ),
        }
    })
    .await;
    let make_endpoint = |key| {
        endpoint(&json!({
            "type": "openai", "baseUrl": url, "apiKey": key, "headers": {}
        }))
    };
    let resolver = Resolver::endpoints(vec![
        make_endpoint("synthetic-old-key"),
        make_endpoint("synthetic-new-key"),
    ]);
    let transport = ReqwestTransport::new(None);
    let result = run(&resolver, &transport, &minimal_request(), None).await;
    let error = run_err(&resolver, &transport, &minimal_request()).await;
    task.abort();
    let _ = task.await;
    assert!(result.is_ok());
    assert_eq!(kind_of(&error), "http");
    assert_eq!(details(&error).retry_after_ms(), Some(1501));
    assert_eq!(details(&error).request_id(), Some("req:[REDACTED]"));
    assert_eq!(count.load(Ordering::SeqCst), 3);
    let hits = hits.lock().unwrap();
    assert_eq!(hits.len(), 3);
    assert_ne!(
        hits[0].headers["authorization"],
        hits[1].headers["authorization"]
    );
    assert_eq!(hits[0].body, hits[1].body);
}

async fn consume_request(socket: &mut tokio::net::TcpStream) {
    let mut bytes = Vec::new();
    let (head_end, length) = loop {
        assert!(socket.read_buf(&mut bytes).await.unwrap() > 0);
        if let Some(end) = bytes.windows(4).position(|window| window == b"\r\n\r\n") {
            let head = String::from_utf8_lossy(&bytes[..end]);
            let length: usize = head
                .lines()
                .filter_map(|line| line.split_once(':'))
                .find_map(|(name, value)| {
                    name.eq_ignore_ascii_case("content-length")
                        .then(|| value.trim().parse().unwrap())
                })
                .unwrap_or(0);
            break (end + 4, length);
        }
    };
    while bytes.len() < head_end + length {
        assert!(socket.read_buf(&mut bytes).await.unwrap() > 0);
    }
}

#[tokio::test]
async fn real_slow_bodies_close_on_sdk_deadline_and_injected_timeout_stays_network() {
    for (status, injected_timeout) in [(200, false), (401, false), (200, true)] {
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let address = listener.local_addr().unwrap();
        let mut server = tokio::spawn(async move {
            let (mut socket, _) = listener.accept().await.unwrap();
            consume_request(&mut socket).await;
            let head = format!("HTTP/1.1 {status} Status\r\nContent-Length: 10000\r\n\r\n{{");
            socket.write_all(head.as_bytes()).await.unwrap();
            let mut byte = [0u8; 1];
            match socket.read(&mut byte).await {
                Ok(0) => {}
                Err(error)
                    if matches!(
                        error.kind(),
                        std::io::ErrorKind::ConnectionReset | std::io::ErrorKind::ConnectionAborted
                    ) => {}
                result => panic!("dropped response kept the socket open: {result:?}"),
            }
        });
        let resolver = Resolver::endpoints(vec![endpoint(&json!({
            "type": "openai", "baseUrl": format!("http://{address}"), "headers": {}
        }))]);
        let client = injected_timeout.then(|| {
            reqwest::Client::builder()
                .timeout(Duration::from_millis(100))
                .redirect(reqwest::redirect::Policy::none())
                .build()
                .unwrap()
        });
        let transport = ReqwestTransport::new(client);
        let error = execute(
            |model| resolver.resolve(model),
            &transport,
            &minimal_request(),
            None,
            Some(Duration::from_millis(500)),
        )
        .await
        .unwrap_err();
        let closed = tokio::time::timeout(Duration::from_secs(3), &mut server).await;
        if closed.is_err() {
            server.abort();
            let _ = server.await;
        }
        assert!(
            matches!(closed, Ok(Ok(()))),
            "server must observe body closure"
        );
        assert_eq!(
            kind_of(&error),
            if injected_timeout {
                "network"
            } else {
                "timeout"
            }
        );
        assert_eq!(resolver.seen().len(), 1);
        if !injected_timeout {
            assert!(std::error::Error::source(details(&error)).is_none());
        }
    }
}
