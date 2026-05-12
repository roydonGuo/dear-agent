package com.roydon.dear.session.req;

import lombok.Data;

@Data
public class SessionEditRequest {
    private String title;
    private Long promptId;
}
