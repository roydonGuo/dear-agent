package com.roydon.dear.knowledge.domain.bo;

import com.roydon.dear.knowledge.enums.FileSplitType;
import lombok.Builder;
import lombok.experimental.Accessors;

/**
 * FileSplitBO
 *
 * @AUTHOR: roydon
 * @DATE: 2026/5/23
 **/
@Builder
@Accessors(chain = true)
public record FileSplitBO(FileSplitType splitType,
                          Integer chunkSize,
                          Integer overlap,
                          Integer titleLevel,
                          String separator,
                          String regex) {
}
