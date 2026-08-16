package com.award.log.service;

import com.award.log.service.IntentRecognitionService.RecognitionResult;
import org.springframework.data.elasticsearch.client.elc.NativeQuery;

public interface NLQueryService {

    class QueryContext {
        private RecognitionResult recognitionResult;
        private NativeQuery elasticsearchQuery;
        private String generatedLuceneQuery;
        private String explanation;

        public QueryContext(RecognitionResult recognitionResult) {
            this.recognitionResult = recognitionResult;
        }

        public RecognitionResult getRecognitionResult() {
            return recognitionResult;
        }

        public void setElasticsearchQuery(NativeQuery elasticsearchQuery) {
            this.elasticsearchQuery = elasticsearchQuery;
        }

        public NativeQuery getElasticsearchQuery() {
            return elasticsearchQuery;
        }

        public void setGeneratedLuceneQuery(String generatedLuceneQuery) {
            this.generatedLuceneQuery = generatedLuceneQuery;
        }

        public String getGeneratedLuceneQuery() {
            return generatedLuceneQuery;
        }

        public void setExplanation(String explanation) {
            this.explanation = explanation;
        }

        public String getExplanation() {
            return explanation;
        }
    }

    QueryContext buildQuery(RecognitionResult recognitionResult);

    String generateLuceneQuery(RecognitionResult recognitionResult);

    String generateElasticsearchDsl(RecognitionResult recognitionResult);
}