package com.roydon.dear.prompt.resp;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class PageResult<T> {

    private Integer pageNum;

    private Integer pageSize;

    private Long total;

    private List<T> records;
}
