package com.award.log.service.impl;

import co.elastic.clients.elasticsearch._types.query_dsl.BoolQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch._types.query_dsl.TermQuery;
import com.award.log.service.IntentRecognitionService.Intent;
import com.award.log.service.IntentRecognitionService.RecognitionResult;
import com.award.log.service.NLQueryService;
import com.award.log.service.NLQueryService.QueryContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.elasticsearch.client.elc.NativeQuery;
import org.springframework.data.elasticsearch.client.elc.NativeQueryBuilder;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
public class NLQueryServiceImpl implements NLQueryService {

    @Override
    public QueryContext buildQuery(RecognitionResult recognitionResult) {
        QueryContext context = new QueryContext(recognitionResult);

        try {
            NativeQuery query = buildElasticsearchQuery(recognitionResult);
            context.setElasticsearchQuery(query);

            String luceneQuery = generateLuceneQuery(recognitionResult);
            context.setGeneratedLuceneQuery(luceneQuery);

            String explanation = generateExplanation(recognitionResult, luceneQuery);
            context.setExplanation(explanation);

            log.info("查询上下文构建成功: luceneQuery={}", luceneQuery);
        } catch (Exception e) {
            log.error("构建查询上下文失败", e);
        }

        return context;
    }

    @Override
    public String generateLuceneQuery(RecognitionResult recognitionResult) {
        StringBuilder query = new StringBuilder();
        Intent intent = recognitionResult.getIntent();
        List<String> keywords = recognitionResult.getKeywords();

        switch (intent) {
            case QUERY_ANOMALIES:
            case QUERY_ERRORS:
                query.append("severity:(ERROR OR FATAL OR WARNING)");
                if (keywords.contains("FATAL")) {
                    query.append(" AND severity:FATAL");
                } else if (keywords.contains("ERROR")) {
                    query.append(" AND severity:ERROR");
                }
                break;

            case DIAGNOSE_ISSUE:
                query.append("severity:(ERROR OR FATAL)");
                break;

            case STATISTICS:
                query.append("*:*");
                break;

            default:
                query.append("*:*");
        }

        if (recognitionResult.getTargetService() != null) {
            query.append(" AND protocol:").append(recognitionResult.getTargetService());
        }

        return query.toString();
    }

    @Override
    public String generateElasticsearchDsl(RecognitionResult recognitionResult) {
        QueryContext context = buildQuery(recognitionResult);
        return context.getGeneratedLuceneQuery();
    }

    private NativeQuery buildElasticsearchQuery(RecognitionResult recognitionResult) {
        Intent intent = recognitionResult.getIntent();
        List<Query> shouldQueries = new ArrayList<>();
        List<Query> filterQueries = new ArrayList<>();

        switch (intent) {
            case QUERY_ANOMALIES:
            case QUERY_ERRORS:
            case DIAGNOSE_ISSUE:
                boolean hasSeverityKeyword = false;

                if (recognitionResult.getKeywords().contains("FATAL")) {
                    shouldQueries.add(Query.of(q -> q.term(t -> t.field("severity").value("FATAL_LEVEL"))));
                    hasSeverityKeyword = true;
                }
                if (recognitionResult.getKeywords().contains("ERROR")) {
                    shouldQueries.add(Query.of(q -> q.term(t -> t.field("severity").value("ERROR_LEVEL"))));
                    hasSeverityKeyword = true;
                }
                if (recognitionResult.getKeywords().contains("WARNING")) {
                    shouldQueries.add(Query.of(q -> q.term(t -> t.field("severity").value("WARNING_LEVEL"))));
                    hasSeverityKeyword = true;
                }

                if (!hasSeverityKeyword) {
                    shouldQueries.add(Query.of(q -> q.term(t -> t.field("severity").value("ERROR_LEVEL"))));
                    shouldQueries.add(Query.of(q -> q.term(t -> t.field("severity").value("FATAL_LEVEL"))));
                    shouldQueries.add(Query.of(q -> q.term(t -> t.field("severity").value("WARNING_LEVEL"))));
                }
                break;

            default:
                break;
        }

        if (recognitionResult.getTargetService() != null) {
            filterQueries.add(Query.of(q -> q.term(t -> t.field("protocol").value(recognitionResult.getTargetService()))));
        }

        BoolQuery.Builder boolQueryBuilder = new BoolQuery.Builder();

        if (!shouldQueries.isEmpty()) {
            boolQueryBuilder.should(shouldQueries);
            boolQueryBuilder.minimumShouldMatch("1");
        }

        if (!filterQueries.isEmpty()) {
            boolQueryBuilder.filter(filterQueries);
        }

        NativeQueryBuilder nativeQueryBuilder = NativeQuery.builder()
                .withQuery(Query.of(q -> q.bool(boolQueryBuilder.build())))
                .withPageable(org.springframework.data.domain.PageRequest.of(0, 100));

        return nativeQueryBuilder.build();
    }

    private String generateExplanation(RecognitionResult recognitionResult, String luceneQuery) {
        StringBuilder explanation = new StringBuilder();
        Intent intent = recognitionResult.getIntent();

        explanation.append("我理解您的需求是：");

        switch (intent) {
            case QUERY_ANOMALIES:
                explanation.append("查询最近的异常日志");
                break;
            case QUERY_ERRORS:
                explanation.append("查询错误日志");
                break;
            case DIAGNOSE_ISSUE:
                explanation.append("诊断分析系统问题");
                break;
            case GENERATE_REPORT:
                explanation.append("生成分析报告");
                break;
            case STATISTICS:
                explanation.append("进行数据统计分析");
                break;
            default:
                explanation.append("执行日志查询");
        }

        if (recognitionResult.getTimeRange() != null) {
            explanation.append("，时间范围：").append(recognitionResult.getTimeRange().getDuration());
        }

        if (recognitionResult.getTargetService() != null) {
            explanation.append("，关注服务：").append(recognitionResult.getTargetService());
        }

        explanation.append("。");

        return explanation.toString();
    }
}