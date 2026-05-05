package com.roydon.dear.common.domain.mcp;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class McpServerVO {

    private String name;
    private String label;
    private String description;
    private String transport;
    private Boolean enabled;
    private List<McpToolVO> tools;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class McpToolVO {
        private String name;
        private String description;
    }
}
