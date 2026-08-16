package com.award.log.decision;

import org.springframework.stereotype.Service;

@Service
public class CollaborativeDecisionService {

    private final IntelligentRouter intelligentRouter;
    private final DecisionTraceService decisionTraceService;

    public CollaborativeDecisionService(IntelligentRouter intelligentRouter,
                                        DecisionTraceService decisionTraceService) {
        this.intelligentRouter = intelligentRouter;
        this.decisionTraceService = decisionTraceService;
    }

    public DecisionResult decide(DecisionInput input) {
        long start = System.currentTimeMillis();
        DecisionResult result = intelligentRouter.decide(input);
        decisionTraceService.record(input, result, System.currentTimeMillis() - start);
        return result;
    }
}
