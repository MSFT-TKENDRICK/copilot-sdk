#![cfg(test)]

use super::*;

async fn frame(reader: &mut tokio::io::DuplexStream) -> Value {
    let mut header = Vec::new();
    while !header.ends_with(b"\r\n\r\n") {
        header.push(reader.read_u8().await.unwrap());
    }
    let length: usize = std::str::from_utf8(&header)
        .unwrap()
        .trim()
        .strip_prefix("Content-Length: ")
        .unwrap()
        .parse()
        .unwrap();
    let mut body = vec![0; length];
    reader.read_exact(&mut body).await.unwrap();
    serde_json::from_slice(&body).unwrap()
}

async fn reply(writer: &mut tokio::io::DuplexStream, request: &Value, result: Value) {
    let payload = json!({"jsonrpc": "2.0", "id": request["id"], "result": result});
    write_frame(writer, &payload).await;
}

async fn write_frame(writer: &mut tokio::io::DuplexStream, payload: &Value) {
    let body = serde_json::to_vec(payload).unwrap();
    writer
        .write_all(format!("Content-Length: {}\r\n\r\n", body.len()).as_bytes())
        .await
        .unwrap();
    writer.write_all(&body).await.unwrap();
    writer.flush().await.unwrap();
}

async fn session_pair() -> (
    crate::Client,
    Session,
    tokio::io::DuplexStream,
    tokio::io::DuplexStream,
) {
    let (client_write, mut server_read) = tokio::io::duplex(8192);
    let (mut server_write, client_read) = tokio::io::duplex(8192);
    let client = crate::Client::from_streams(
        client_read,
        client_write,
        PathBuf::from(env!("CARGO_MANIFEST_DIR")),
    )
    .unwrap();
    let config = crate::SessionConfig::default()
        .with_permission_handler(Arc::new(crate::handler::ApproveAllHandler));
    let (session, ()) = tokio::time::timeout(Duration::from_secs(5), async {
        tokio::join!(client.create_session(config), async {
            let request = frame(&mut server_read).await;
            assert_eq!(request["method"], "session.create");
            reply(
                &mut server_write,
                &request,
                json!({
                    "sessionId": request["params"]["sessionId"],
                    "workspacePath": env!("CARGO_MANIFEST_DIR"),
                }),
            )
            .await;
        })
    })
    .await
    .unwrap();
    (client, session.unwrap(), server_read, server_write)
}

#[tokio::test]
async fn public_create_signatures_options_and_http_error_wiring() {
    let (_client, session, mut reader, mut writer) = session_pair().await;
    let (url, hits, server) = serve(|_| {
        (
            429,
            vec![
                ("retry-after-ms".into(), "0.1".into()),
                ("x-request-id".into(), "req:synthetic-public-key".into()),
            ],
            "{\"error\":{\"message\":\"synthetic-public-key\",\"type\":\"bad_input\"}}".into(),
        )
    })
    .await;
    let api = session.decisions();
    for method in 0..3 {
        let request = minimal_request();
        let options = DecisionsOptions {
            endpoint_model_id: Some("synthetic-endpoint-model".to_owned()),
            http_client: None,
        };
        let call = async {
            match method {
                0 => api.create(request).await,
                1 => api.create_with_options(request, options).await,
                _ => api.create_with_timeout(request, options, None).await,
            }
        };
        let endpoint = json!({
            "type": "openai", "baseUrl": url, "headers": {}, "apiKey": "synthetic-public-key",
        });
        let (result, ()) = tokio::time::timeout(Duration::from_secs(5), async {
            tokio::join!(call, async {
                let request = frame(&mut reader).await;
                assert_eq!(request["method"], "session.provider.getEndpoint");
                if method == 0 {
                    assert!(request["params"].get("modelId").is_none());
                } else {
                    assert_eq!(request["params"]["modelId"], "synthetic-endpoint-model");
                }
                reply(&mut writer, &request, endpoint).await;
            })
        })
        .await
        .unwrap();
        let error = result.unwrap_err();
        assert_eq!(kind_of(&error), "http");
        assert_eq!(details(&error).retry_after_ms(), Some(1));
        assert_eq!(details(&error).request_id(), Some("req:[REDACTED]"));
        assert!(!format!("{error:?}").contains("synthetic-public-key"));
    }
    server.abort();
    let _ = server.await;
    assert_eq!(hits.lock().unwrap().len(), 3);
}

#[tokio::test(start_paused = true)]
async fn public_defaults_expire_endpoint_rpc_and_zero_does_not_resolve() {
    let (_client, session, mut reader, mut writer) = session_pair().await;
    let api = session.decisions();
    for with_options in [false, true] {
        let mut call = Box::pin(async {
            if with_options {
                api.create_with_options(
                    minimal_request(),
                    DecisionsOptions {
                        endpoint_model_id: None,
                        http_client: None,
                    },
                )
                .await
            } else {
                api.create(minimal_request()).await
            }
        });
        let rpc_request = tokio::select! {
            result = &mut call => panic!("endpoint RPC must still be pending: {result:?}"),
            request = frame(&mut reader) => request,
        };
        assert_eq!(rpc_request["method"], "session.provider.getEndpoint");
        tokio::time::advance(Duration::from_secs(599)).await;
        assert!(matches!(futures_util::poll!(&mut call), Poll::Pending));
        tokio::time::advance(Duration::from_secs(1)).await;
        let error = call.await.unwrap_err();
        assert_eq!(kind_of(&error), "timeout");
        assert!(std::error::Error::source(details(&error)).is_none());
        // The real RPC transport handles a late response after the Decisions
        // future is gone. No HTTP continuation remains to consume it.
        reply(
            &mut writer,
            &rpc_request,
            json!({
                "type": "openai", "baseUrl": "http://127.0.0.1:0", "headers": {},
            }),
        )
        .await;
        tokio::task::yield_now().await;
    }
    let error = api
        .create_with_timeout(
            minimal_request(),
            DecisionsOptions {
                endpoint_model_id: None,
                http_client: None,
            },
            Some(Duration::ZERO),
        )
        .await
        .unwrap_err();
    assert_eq!(kind_of(&error), "timeout");
    let mut request = minimal_request();
    request.model.clear();
    let error = api
        .create_with_timeout(request, DecisionsOptions::default(), Some(Duration::ZERO))
        .await
        .unwrap_err();
    assert_eq!(error.kind(), &ErrorKind::InvalidConfig);
    assert!(matches!(
        futures_util::poll!(Box::pin(frame(&mut reader))),
        Poll::Pending
    ));
}

#[tokio::test]
async fn public_endpoint_failure_discards_raw_rpc_error_data() {
    let (_client, session, mut reader, mut writer) = session_pair().await;
    let api = session.decisions();
    let (result, ()) = tokio::join!(api.create(minimal_request()), async {
        let request = frame(&mut reader).await;
        assert_eq!(request["method"], "session.provider.getEndpoint");
        write_frame(&mut writer, &json!({
            "jsonrpc": "2.0", "id": request["id"],
            "error": {
                "code": -32000,
                "message": "synthetic-unknown-rpc-secret",
                "data": {"url": "synthetic-private-url", "headers": {"x-token": "synthetic-private-header"}}
            }
        })).await;
    });
    let error = result.unwrap_err();
    assert_eq!(kind_of(&error), "endpointUnavailable");
    assert!(std::error::Error::source(details(&error)).is_none());
    let debug = format!("{error:?}");
    for secret in [
        "synthetic-unknown-rpc-secret",
        "synthetic-private-url",
        "synthetic-private-header",
    ] {
        assert!(!debug.contains(secret));
    }
}
