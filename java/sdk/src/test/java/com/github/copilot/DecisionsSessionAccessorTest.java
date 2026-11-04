/*---------------------------------------------------------------------------------------------
 *  Copyright (c) Microsoft Corporation. All rights reserved.
 *--------------------------------------------------------------------------------------------*/

package com.github.copilot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.copilot.decisions.DecisionsErrorKind;
import com.github.copilot.decisions.DecisionsException;
import com.github.copilot.decisions.DecisionsOptions;
import com.github.copilot.decisions.DecisionsQuestion;
import com.github.copilot.decisions.DecisionsRequest;
import com.github.copilot.decisions.DecisionsResult;
import com.github.copilot.decisions.SessionDecisions;

/**
 * Verifies {@link CopilotSession#getDecisions()} and that it resolves the
 * endpoint through {@code session.provider.getEndpoint}.
 */
class DecisionsSessionAccessorTest {

    private static final ObjectMapper MAPPER = JsonRpcClient.getObjectMapper();

    private static DecisionsRequest request() {
        return new DecisionsRequest().setModel("gpt-6-luna").setInput("x")
                .setQuestions(List.of(new DecisionsQuestion.Predicate("q")));
    }

    @Test
    void getDecisionsIsLazyAndCached() throws Exception {
        try (var sockets = new SocketPair()) {
            var session = new CopilotSession("sess-decisions", sockets.rpc);
            try {
                SessionDecisions decisions = session.getDecisions();
                assertNotNull(decisions);
                assertSame(decisions, session.getDecisions());
            } finally {
                session.forceStopLocally();
            }
        }
    }

    @Test
    void createResolvesEndpointThroughSessionRpc() throws Exception {
        try (var sockets = new SocketPair()) {
            var session = new CopilotSession("sess-decisions", sockets.rpc);
            try {
                ObjectNode anthropic = MAPPER.createObjectNode();
                anthropic.put("type", "anthropic");
                anthropic.put("baseUrl", "https://api.anthropic.com");
                anthropic.putObject("headers");

                CompletableFuture<DecisionsResult> withModel = session.getDecisions().create(request(),
                        new DecisionsOptions().setEndpointModelId("endpoint-model"));
                JsonNode first = sockets.readOneMessage();
                assertEquals("session.provider.getEndpoint", first.get("method").asText());
                assertEquals("sess-decisions", first.get("params").get("sessionId").asText());
                assertEquals("endpoint-model", first.get("params").get("modelId").asText());
                sockets.respond(first, anthropic);
                assertUnsupported(withModel);

                CompletableFuture<DecisionsResult> withoutModel = session.getDecisions().create(request());
                JsonNode second = sockets.readOneMessage();
                assertEquals("session.provider.getEndpoint", second.get("method").asText());
                assertEquals("sess-decisions", second.get("params").get("sessionId").asText());
                JsonNode modelId = second.get("params").get("modelId");
                assertTrue(modelId == null || modelId.isNull());
                sockets.respond(second, anthropic);
                assertUnsupported(withoutModel);
            } finally {
                session.forceStopLocally();
            }
        }
    }

    private static void assertUnsupported(CompletableFuture<DecisionsResult> future) throws Exception {
        try {
            future.get(5, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            DecisionsException error = assertInstanceOf(DecisionsException.class, e.getCause());
            assertEquals(DecisionsErrorKind.UNSUPPORTED_PROVIDER, error.getKind());
            return;
        }
        throw new AssertionError("expected unsupportedProvider");
    }

    /** A loopback socket pair whose server side acts as a JSON-RPC stub. */
    private static final class SocketPair implements AutoCloseable {
        final Socket clientSocket;
        final Socket serverSocket;
        final JsonRpcClient rpc;
        private final InputStream in;
        private final OutputStream out;

        SocketPair() throws Exception {
            var loopback = InetAddress.getLoopbackAddress();
            try (var ss = new ServerSocket(0, 1, loopback)) {
                clientSocket = new Socket(loopback, ss.getLocalPort());
                serverSocket = ss.accept();
            }
            serverSocket.setSoTimeout(5000);
            rpc = JsonRpcClient.fromSocket(clientSocket);
            in = serverSocket.getInputStream();
            out = serverSocket.getOutputStream();
        }

        JsonNode readOneMessage() throws Exception {
            var header = new StringBuilder();
            int b;
            while ((b = in.read()) != -1) {
                if (b == '\n' && header.toString().endsWith("\r")) {
                    break;
                }
                header.append((char) b);
            }
            in.read();
            in.read();
            String hdr = header.toString().trim();
            int len = Integer.parseInt(hdr.substring(hdr.indexOf(':') + 1).trim());
            return MAPPER.readTree(in.readNBytes(len));
        }

        void respond(JsonNode request, JsonNode result) throws Exception {
            var response = MAPPER.createObjectNode();
            response.put("jsonrpc", "2.0");
            response.set("id", request.get("id"));
            response.set("result", result);
            byte[] body = MAPPER.writeValueAsBytes(response);
            out.write(("Content-Length: " + body.length + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            out.write(body);
            out.flush();
        }

        @Override
        public void close() throws Exception {
            rpc.close();
            clientSocket.close();
            serverSocket.close();
        }
    }
}
