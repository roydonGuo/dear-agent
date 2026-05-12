package com.roydon.dear.session.resp;

import lombok.Builder;
import lombok.Data;

import java.io.Serializable;
import java.util.List;

@Data
@Builder
public class PageResult<T> implements Serializable {
    private Integer pageNum;
    private Integer pageSize;
    private Long total;
    private List<T> records;
}
