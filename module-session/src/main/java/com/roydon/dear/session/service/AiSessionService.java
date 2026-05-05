package com.roydon.dear.session.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.roydon.dear.session.entity.AiSession;
import com.roydon.dear.session.req.SaveQuestionRequest;
import com.roydon.dear.session.req.UpdateAnswerRequest;

import java.util.List;

public interface AiSessionService extends IService<AiSession> {

    List<AiSession> findRecentBySessionId(String sessionId, int maxRecords);

    AiSession saveQuestion(SaveQuestionRequest request);

    boolean updateAnswer(UpdateAnswerRequest request);
}
