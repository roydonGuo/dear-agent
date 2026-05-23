package com.roydon.dear.knowledge.rag.config;

import com.roydon.dear.model.registry.ModelRegistry;
import com.roydon.dear.session.enums.ModelCategoryEnum;
import org.apache.hc.client5.http.impl.async.HttpAsyncClientBuilder;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.http.message.BasicHeader;
import org.elasticsearch.client.RestClient;
import org.elasticsearch.client.RestClientBuilder;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.elasticsearch.ElasticsearchVectorStore;
import org.springframework.ai.vectorstore.elasticsearch.ElasticsearchVectorStoreOptions;
import org.springframework.ai.vectorstore.elasticsearch.SimilarityFunction;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.util.List;

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
                .setHttpClientConfigCallback(new RestClientBuilder.HttpClientConfigCallback() {
                    @Override
                    public HttpAsyncClientBuilder customizeHttpClient(HttpAsyncClientBuilder httpClientBuilder) {
                        return httpClientBuilder.setDefaultHeaders(List.of(
                                new BasicHeader("Authorization", "ApiKey " + properties.getApiKey())
                        ));
                    }
                })
                .build();
    }

    @Primary
    @Bean
    @ConditionalOnMissingBean
    public ElasticsearchVectorStore elasticsearchVectorStore(RestClient restClient) {
        EmbeddingModel embeddingModel = modelRegistry.getDefaultEmbeddingModel(ModelCategoryEnum.EMBEDDING.getCode());

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
