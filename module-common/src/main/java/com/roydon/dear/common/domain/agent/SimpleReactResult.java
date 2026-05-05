package com.roydon.dear.common.domain.agent;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
@AllArgsConstructor
public class SimpleReactResult {
    private String answer;
    private List<SearchResult> searchResults;

    public List<SearchResult> getSearchResults() {
        return searchResults;
    }
}
