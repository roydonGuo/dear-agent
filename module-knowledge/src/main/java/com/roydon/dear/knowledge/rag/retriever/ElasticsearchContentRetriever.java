package com.roydon.dear.knowledge.rag.retriever;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.roydon.dear.knowledge.rag.config.ElasticSearchProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.elasticsearch.client.Request;
import org.elasticsearch.client.Response;
import org.elasticsearch.client.RestClient;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 基于 Elasticsearch RestClient 的关键词检索器
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ElasticsearchContentRetriever implements ContentRetriever {

    private final RestClient restClient;
    private final ElasticSearchProperties properties;

    @Override
    public List<Document> keywordSearch(String query, List<Long> fileIdFilters, int topK) {
        try {
            JSONObject body = buildKeywordQuery(query, fileIdFilters, topK);
            Request request = new Request("POST", "/" + properties.getIndexName() + "/_search");
            request.setJsonEntity(body.toJSONString());

            Response response = restClient.performRequest(request);
            String responseBody = new BufferedReader(
                    new InputStreamReader(response.getEntity().getContent(), StandardCharsets.UTF_8))
                    .lines()
                    .collect(Collectors.joining("\n"));

            return parseKeywordResults(responseBody);
        } catch (Exception e) {
            log.error("关键词检索失败: query={}", query, e);
            return Collections.emptyList();
        }
    }

    private JSONObject buildKeywordQuery(String query, List<Long> fileIdFilters, int topK) {
        JSONObject body = new JSONObject();
        body.put("size", topK);

        JSONObject boolQuery = new JSONObject();
        JSONArray mustClauses = new JSONArray();

        JSONObject matchClause = new JSONObject();
        matchClause.put("match", Map.of("content", query));
        mustClauses.add(matchClause);

        if (fileIdFilters != null && !fileIdFilters.isEmpty()) {
            JSONObject termsClause = new JSONObject();
            termsClause.put("terms", Map.of("metadata.fileId", fileIdFilters));
            boolQuery.put("filter", termsClause);
        }

        boolQuery.put("must", mustClauses);
        JSONObject queryWrapper = new JSONObject();
        queryWrapper.put("bool", boolQuery);
        body.put("query", queryWrapper);

        return body;
    }

    private List<Document> parseKeywordResults(String responseBody) {
        JSONObject resp = JSON.parseObject(responseBody);
        JSONObject hits = resp.getJSONObject("hits");
        if (hits == null) return Collections.emptyList();

        JSONArray hitArray = hits.getJSONArray("hits");
        if (hitArray == null || hitArray.isEmpty()) return Collections.emptyList();

        List<Document> results = new ArrayList<>();
        for (int i = 0; i < hitArray.size(); i++) {
            JSONObject hit = hitArray.getJSONObject(i);
            String docId = hit.getString("_id");
            double score = hit.getDoubleValue("_score");

            // 从 _source 中提取字段
            Map<String, Object> metadata = new HashMap<>();
            String content = "";
            JSONObject source = hit.getJSONObject("_source");
            if (source != null) {
                content = source.getString("content");
                Object rawMeta = source.get("metadata");
                if (rawMeta instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> raw = (Map<String, Object>) rawMeta;
                    metadata.putAll(raw);
                }
            }

            Document doc = Document.builder()
                    .id(docId)
                    .text(content)
                    .metadata(metadata)
                    .score(score)
                    .build();
            results.add(doc);
        }
        return results;
    }
}
