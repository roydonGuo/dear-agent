package com.roydon.dear.model.provider;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpCookie;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.reactive.ClientHttpRequest;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.BodyInserter;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * WebClient 过滤器：在 HTTP 层面拦截 DeepSeek API 请求，修复 reasoning_content 丢失问题。
 *
 * <p>Spring AI 1.1.0 的 DeepSeekChatModel.createRequest() 在序列化 Assistant 消息时
 * 始终将 reasoningContent 设为 null（即使 DeepSeekAssistantMessage 已携带该字段），
 * 导致多轮工具调用时 DeepSeek API 返回 400。</p>
 *
 * <p>本过滤器包裹原始 BodyInserter，捕获序列化后的 JSON body，
 * 为最后一条 assistant 消息注入 reasoning_content。</p>
 */
public class DeepSeekReasoningExchangeFilter implements ExchangeFilterFunction {

    private static final Logger log = LoggerFactory.getLogger(DeepSeekReasoningExchangeFilter.class);
    private static final DefaultDataBufferFactory bufferFactory = new DefaultDataBufferFactory();

    @Override
    public Mono<ClientResponse> filter(ClientRequest request, ExchangeFunction next) {
        String reasoning = DeepSeekReasoningCache.poll();
        if (reasoning == null || reasoning.isBlank()) {
            return next.exchange(request);
        }

        @SuppressWarnings("unchecked")
        BodyInserter<?, ? super ClientHttpRequest> typedBody =
                (BodyInserter<?, ? super ClientHttpRequest>) request.body();
        BodyInserter<?, ? super ClientHttpRequest> wrappedBody = (outputMessage, context) -> {
            BodyCapture capture = new BodyCapture(request.method(), request.url());
            return typedBody.insert(capture, context)
                    .then(Mono.defer(() -> {
                        byte[] raw = capture.getBytes();
                        byte[] modified = injectReasoningContent(raw, reasoning);
                        return outputMessage.writeWith(
                                Mono.just(bufferFactory.wrap(modified)));
                    }));
        };

        ClientRequest modifiedRequest = ClientRequest.from(request)
                .headers(headers -> {
                    headers.remove(HttpHeaders.CONTENT_LENGTH);
                })
                .body(wrappedBody)
                .build();
        return next.exchange(modifiedRequest);
    }

    // ===== 内部类：捕获写入的请求体字节 =====

    /**
     * 实现 o.s.http.client.reactive.ClientHttpRequest 以捕获 BodyInserter 写入的原始字节。
     */
    @SuppressWarnings("unchecked")
    private static class BodyCapture implements ClientHttpRequest {

        private final HttpMethod method;
        private final URI uri;
        private final HttpHeaders headers = new HttpHeaders();
        private volatile byte[] capturedBytes;

        BodyCapture(HttpMethod method, URI uri) {
            this.method = method;
            this.uri = uri;
        }

        // ===== ReactiveHttpOutputMessage =====

        @Override
        public void beforeCommit(
                java.util.function.Supplier<? extends Mono<Void>> action) {
            // no-op for capture
        }

        @Override
        public boolean isCommitted() {
            return false;
        }

        @Override
        public Mono<Void> writeWith(
                org.reactivestreams.Publisher<? extends DataBuffer> body) {
            return DataBufferUtils.join(Flux.from(body))
                    .doOnNext(buf -> {
                        this.capturedBytes = new byte[buf.readableByteCount()];
                        buf.read(this.capturedBytes);
                        DataBufferUtils.release(buf);
                    })
                    .then();
        }

        @Override
        public Mono<Void> writeAndFlushWith(
                org.reactivestreams.Publisher<? extends org.reactivestreams.Publisher<? extends DataBuffer>> body) {
            return writeWith(Flux.from(body).flatMap(Flux::from));
        }

        @Override
        public Mono<Void> setComplete() {
            return Mono.empty();
        }

        // ===== HttpOutputMessage =====

        @Override
        public org.springframework.core.io.buffer.DataBufferFactory bufferFactory() {
            return bufferFactory;
        }

        // ===== HttpMessage =====

        @Override
        public HttpHeaders getHeaders() {
            return headers;
        }

        // ===== ClientHttpRequest (reactive) =====

        @Override
        public HttpMethod getMethod() {
            return method;
        }

        @Override
        public URI getURI() {
            return uri;
        }

        @Override
        public MultiValueMap<String, HttpCookie> getCookies() {
            return new org.springframework.util.LinkedMultiValueMap<>();
        }

        @Override
        public Map<String, Object> getAttributes() {
            return new HashMap<>();
        }

        @Override
        public <T> T getNativeRequest() {
            return null;
        }

        // ===== 自定义 =====

        public byte[] getBytes() {
            return capturedBytes != null ? capturedBytes : new byte[0];
        }
    }

    // ===== JSON 注入逻辑 =====

    private static byte[] injectReasoningContent(byte[] raw, String reasoning) {
        if (raw == null || raw.length == 0) return raw;
        try {
            String body = new String(raw, StandardCharsets.UTF_8);
            JSONObject json = JSON.parseObject(body);
            if (json == null) return raw;

            JSONArray messages = json.getJSONArray("messages");
            if (messages == null || messages.isEmpty()) return raw;

            for (int i = messages.size() - 1; i >= 0; i--) {
                JSONObject msg = messages.getJSONObject(i);
                if ("assistant".equals(msg.getString("role"))) {
                    if (!msg.containsKey("reasoning_content") || msg.getString("reasoning_content") == null) {
                        msg.put("reasoning_content", reasoning);
                        log.debug("已将 reasoning_content({} chars) 注入请求体", reasoning.length());
                    }
                    break;
                }
            }

            return JSON.toJSONBytes(json);
        } catch (Exception e) {
            log.warn("注入 reasoning_content 失败: {}", e.getMessage());
            return raw;
        }
    }
}
