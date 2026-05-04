package com.roydon.dear.tts;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Adds realtime TTS audio events to the existing Agent SSE stream.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentVoiceStreamService {

    private static final int MAX_TTS_TEXT_LENGTH = 220;

    /**
     * 最小 TTS 文本块长度（字符）。
     * 短于此长度的"句子"会继续在 buffer 中累积，避免零散片段触发 TTS。
     */
    private static final int MIN_TTS_CHUNK_LENGTH = 20;

    private final AlibabaTtsService ttsService;

    public Flux<String> withVoice(Flux<String> agentStream, String voice) {
        return Flux.create(sink -> {
            VoiceStreamState state = new VoiceStreamState(sink, voice);

            Disposable ttsWorker = state.ttsRequests().asFlux()
                    .concatMap(request -> ttsService.streamSynthesize(request.text(), state.voice())
                            .subscribeOn(Schedulers.boundedElastic())
                            .map(audioBytes -> createAudioEvent(audioBytes, state.voice(), request.sequence()))
                            .onErrorResume(error -> {
                                log.warn("TTS failed for sentence sequence {}: {}", request.sequence(), error.getMessage());
                                return Flux.empty();
                            }))
                    .subscribe(
                            state::next,
                            state::error,
                            () -> {
                                state.markTtsCompleted();
                                state.completeIfReady();
                            }
                    );
            state.setTtsWorker(ttsWorker);

            Disposable upstream = agentStream.subscribe(
                    event -> handleAgentEvent(event, state),
                    error -> {
                        log.error("Agent stream failed while voice output is enabled", error);
                        state.error(error);
                    },
                    () -> {
                        state.markUpstreamCompleted();
                        flushRemainingText(state);
                        state.completeTtsRequests();
                        state.completeIfReady();
                    }
            );

            state.setUpstream(upstream);
            sink.onCancel(state::cancel);
            sink.onDispose(state::cancel);
        });
    }

    private void handleAgentEvent(String event, VoiceStreamState state) {
        if (state.isTerminated()) {
            return;
        }

        JSONObject json = parseJson(event);
        if (json == null) {
            state.next(event);
            return;
        }

        String type = json.getString("type");
        if ("done".equals(type)) {
            state.setPendingDone(event);
            flushRemainingText(state);
            state.completeTtsRequests();
            state.completeIfReady();
            return;
        }

        state.next(event);

        if ("text".equals(type)) {
            appendTextAndStartTts(json.getString("content"), state);
        }
    }

    private JSONObject parseJson(String event) {
        try {
            return JSON.parseObject(event);
        } catch (Exception e) {
            return null;
        }
    }

    private void appendTextAndStartTts(String content, VoiceStreamState state) {
        if (StringUtils.isBlank(content)) {
            return;
        }

        List<String> sentences;
        synchronized (state.textBuffer()) {
            state.textBuffer().append(content);
            sentences = drainCompletedSentences(state.textBuffer());
        }

        sentences.forEach(sentence -> queueTts(sentence, state));
    }

    private List<String> drainCompletedSentences(StringBuilder buffer) {
        List<String> sentences = new ArrayList<>();
        int endIndex;
        while ((endIndex = findSentenceEnd(buffer)) >= 0) {
            String rawSentence = buffer.substring(0, endIndex + 1);
            int trimmedLen = rawSentence.trim().length();

            // 句子太短且 buffer 还没满 → 留在 buffer 中等更多内容
            if (trimmedLen < MIN_TTS_CHUNK_LENGTH
                    && buffer.length() - (endIndex + 1) + trimmedLen < MAX_TTS_TEXT_LENGTH) {
                break;
            }

            buffer.delete(0, endIndex + 1);
            String cleaned = cleanTextForTts(rawSentence.trim());
            if (StringUtils.isNotBlank(cleaned)) {
                splitLongText(cleaned).forEach(part -> {
                    if (StringUtils.isNotBlank(part)) {
                        sentences.add(part.trim());
                    }
                });
            }
        }
        return sentences;
    }

    private int findSentenceEnd(StringBuilder buffer) {
        for (int i = 0; i < buffer.length(); i++) {
            char ch = buffer.charAt(i);
            if (isSentenceEnd(ch)) {
                return i;
            }
        }
        return -1;
    }

    private boolean isSentenceEnd(char ch) {
        return ch == '。'
                || ch == '！'
                || ch == '？'
                || ch == '；'
                || ch == '.'
                || ch == '!'
                || ch == '?'
                || ch == ';';
    }

    private List<String> splitLongText(String text) {
        List<String> parts = new ArrayList<>();
        if (text.length() <= MAX_TTS_TEXT_LENGTH) {
            parts.add(text);
            return parts;
        }

        for (int start = 0; start < text.length(); start += MAX_TTS_TEXT_LENGTH) {
            int end = Math.min(start + MAX_TTS_TEXT_LENGTH, text.length());
            parts.add(text.substring(start, end));
        }
        return parts;
    }

    /**
     * 清理文本中的 markdown / 标记符号，使 TTS 朗读更自然。
     * - 移除表格分隔行（|---|---|---）
     * - 移除隔离的分隔线（---\n）
     * - 移除加粗/斜体标记 **text* -> text
     * - 移除表格竖线
     * - 移除标题 # 号
     * - 将 markdown 链接 [text](url) 替换为 text
     * - 移除反引号
     * - 合并多余空行/空格
     */
    private String cleanTextForTts(String text) {
        if (text == null) {
            return null;
        }
        // 移除表格分隔行：|---|---|---
        text = text.replaceAll("\\|[-]+\\|[-]+\\|", "");
        // 移除纯分隔线（单独一行 --- 或 ***）
        text = text.replaceAll("(?m)^[-]{3,}$", "");
        text = text.replaceAll("(?m)^[*]{3,}$", "");
        // 移除加粗/斜体标记 **text** -> text / *text* -> text
        text = text.replaceAll("\\*\\*(.*?)\\*\\*", "$1");
        text = text.replaceAll("\\*([^*]+)\\*", "$1");
        // 移除表格竖线
        text = text.replace("|", "");
        // 移除标题标记
        text = text.replaceAll("(?m)^#+\\s*", "");
        // 替换 markdown 链接
        text = text.replaceAll("\\[([^\\]]+)]\\([^)]+\\)", "$1");
        // 移除反引号
        text = text.replace("`", "");
        // 合并多个换行为句号
        text = text.replaceAll("\\n{3,}", "。");
        text = text.replaceAll("\\n+", "。");
        // 合并连续空格
        text = text.replaceAll("\\s{2,}", " ").trim();
        return text;
    }

    private void flushRemainingText(VoiceStreamState state) {
        String remaining;
        synchronized (state.textBuffer()) {
            remaining = state.textBuffer().toString().trim();
            state.textBuffer().setLength(0);
        }

        String cleaned = cleanTextForTts(remaining);
        if (StringUtils.isBlank(cleaned)) {
            return;
        }

        splitLongText(cleaned).forEach(part -> {
            if (StringUtils.isNotBlank(part)) {
                queueTts(part.trim(), state);
            }
        });
    }

    private void queueTts(String sentence, VoiceStreamState state) {
        if (state.isTerminated() || StringUtils.isBlank(sentence)) {
            return;
        }

        int sentenceSeq = state.nextSentenceSequence();
        state.emitTtsRequest(new TtsRequest(sentenceSeq, sentence));
    }

    private String createAudioEvent(byte[] audioBytes, String voice, int sequence) {
        JSONObject json = new JSONObject();
        json.put("type", "audio");
        json.put("content", Base64.getEncoder().encodeToString(audioBytes));
        String eventVoice = StringUtils.defaultIfBlank(voice, "default");
        json.put("data", Map.of(
                "format", "wav",
                "voice", eventVoice,
                "sequence", sequence,
                "timestamp", System.currentTimeMillis()
        ));
        return json.toJSONString();
    }

    private record TtsRequest(int sequence, String text) {
    }

    private static class VoiceStreamState {

        private final FluxSink<String> sink;
        private final String voice;
        private final StringBuilder textBuffer = new StringBuilder();
        private final AtomicBoolean upstreamCompleted = new AtomicBoolean(false);
        private final AtomicBoolean ttsCompleted = new AtomicBoolean(false);
        private final AtomicBoolean ttsRequestsCompleted = new AtomicBoolean(false);
        private final AtomicBoolean terminated = new AtomicBoolean(false);
        private final AtomicInteger sentenceSequence = new AtomicInteger(0);
        private final AtomicReference<String> pendingDone = new AtomicReference<>();
        private final Sinks.Many<TtsRequest> ttsRequests = Sinks.many().unicast().onBackpressureBuffer();
        private final AtomicReference<Disposable> upstream = new AtomicReference<>();
        private final AtomicReference<Disposable> ttsWorker = new AtomicReference<>();

        private VoiceStreamState(FluxSink<String> sink, String voice) {
            this.sink = sink;
            this.voice = voice;
        }

        private StringBuilder textBuffer() {
            return textBuffer;
        }

        private String voice() {
            return voice;
        }

        private Sinks.Many<TtsRequest> ttsRequests() {
            return ttsRequests;
        }

        private void setUpstream(Disposable disposable) {
            upstream.set(disposable);
        }

        private void setTtsWorker(Disposable disposable) {
            ttsWorker.set(disposable);
        }

        private void emitTtsRequest(TtsRequest request) {
            if (!isTerminated() && !ttsRequestsCompleted.get()) {
                ttsRequests.tryEmitNext(request);
            }
        }

        private int nextSentenceSequence() {
            return sentenceSequence.incrementAndGet();
        }

        private void setPendingDone(String event) {
            pendingDone.compareAndSet(null, event);
        }

        private void markUpstreamCompleted() {
            upstreamCompleted.set(true);
        }

        private void markTtsCompleted() {
            ttsCompleted.set(true);
        }

        private void completeTtsRequests() {
            if (ttsRequestsCompleted.compareAndSet(false, true)) {
                ttsRequests.tryEmitComplete();
            }
        }

        private boolean isTerminated() {
            return terminated.get() || sink.isCancelled();
        }

        private void next(String event) {
            if (!isTerminated()) {
                sink.next(event);
            }
        }

        private void error(Throwable error) {
            if (terminated.compareAndSet(false, true)) {
                completeTtsRequests();
                disposeTtsWorker();
                sink.error(error);
            }
        }

        private void completeIfReady() {
            if (!upstreamCompleted.get() && pendingDone.get() == null) {
                return;
            }
            if (!ttsCompleted.get()) {
                return;
            }
            if (terminated.compareAndSet(false, true)) {
                String done = pendingDone.get();
                if (done != null && !sink.isCancelled()) {
                    sink.next(done);
                }
                sink.complete();
            }
        }

        private void cancel() {
            if (!terminated.compareAndSet(false, true)) {
                return;
            }
            Disposable upstreamDisposable = upstream.get();
            if (upstreamDisposable != null && !upstreamDisposable.isDisposed()) {
                upstreamDisposable.dispose();
            }
            disposeTtsWorker();
        }

        private void disposeTtsWorker() {
            Disposable ttsWorkerDisposable = ttsWorker.get();
            if (ttsWorkerDisposable != null && !ttsWorkerDisposable.isDisposed()) {
                ttsWorkerDisposable.dispose();
            }
        }
    }
}
