package com.roydon.dear.knowledge.rag.config;

import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.elasticsearch.ElasticsearchVectorStore;
import org.springframework.ai.vectorstore.elasticsearch.ElasticsearchVectorStoreOptions;
import org.springframework.ai.vectorstore.elasticsearch.SimilarityFunction;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.roydon.dear.model.registry.ModelRegistry;

@Configuration
@EnableConfigurationProperties(ElasticSearchProperties.class)
public class ElasticSearchConfiguration {

    @Autowired
    private ElasticSearchProperties properties;

    @Autowired
    private ModelRegistry modelRegistry;

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    public RestClient restClient() {
        return RestClient
                .builder(HttpHost.create(properties.getHost()))
                .build();
    }

    @Bean
    @ConditionalOnMissingBean
    public ElasticsearchVectorStore elasticsearchVectorStore(RestClient restClient) {
        EmbeddingModel embeddingModel = modelRegistry.getDefaultEmbeddingModel("embedding");

        ElasticsearchVectorStoreOptions options = new ElasticsearchVectorStoreOptions();
        options.setIndexName(properties.getIndexName());
        options.setDimensions(properties.getDimensions());
        options.setSimilarity(SimilarityFunction.valueOf(properties.getSimilarity().toLowerCase()));

        return ElasticsearchVectorStore.builder(restClient, embeddingModel)
                .options(options)
                .initializeSchema(properties.isInitializeSchema())
                .build();
    }
}
