package com.roydon.dear.knowledge.domain.req;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KnowledgeFileRequest {

    @NotNull(message = "知识库ID不能为空")
    private Long baseId;

    private Long parentId;

    @Size(max = 20, message = "名称最长20个字符")
    private String name;

    @NotBlank(message = "类型不能为空")
    @Pattern(regexp = "^(folder|file)$", message = "类型必须为folder或file")
    private String fileType;

    @Size(max = 32, message = "文件格式最长32个字符")
    private String mineType;

    private String content;
}
