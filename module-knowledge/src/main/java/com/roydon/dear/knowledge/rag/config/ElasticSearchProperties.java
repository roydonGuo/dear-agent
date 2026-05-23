package com.roydon.dear.knowledge.rag.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = ElasticSearchProperties.PREFIX)
public class ElasticSearchProperties {
    public static final String PREFIX = "elasticsearch";

    private String host;

    private String apiKey;

    /**
     * Vector dimension for embeddings.
     */
    private int dimensions = 1024;

    /**
     * Similarity function for vector search: cosine, dot_product, l2_norm.
     */
    private String similarity = "cosine";

    /**
     * Elasticsearch index name for vector storage.
     */
    private String indexName = "knowledge-vector";

    /**
     * Whether to initialize the schema (index mapping) on startup.
     */
    private boolean initializeSchema = true;

}
