package com.award.log.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.elasticsearch.annotations.Document;
import org.springframework.data.elasticsearch.annotations.Field;
import org.springframework.data.elasticsearch.annotations.FieldType;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

@Data
@Document(indexName = "log_analysis", createIndex = false)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonDeserialize(using = LogDocument.LogDocumentDeserializer.class)
public class LogDocument {

    @Id
    private String id;

    @Field(type = FieldType.Text, name = "content")
    private String content;

    @Field(type = FieldType.Keyword, name = "severity")
    private String severity;

    @Field(type = FieldType.Keyword, name = "protocol")
    private String protocol;

    @Field(type = FieldType.Date, name = "@timestamp")
    private LocalDateTime timestamp;

    @Field(type = FieldType.Keyword, name = "source")
    private String source;

    @Field(type = FieldType.Keyword, name = "taskId")
    private String taskId;

    @Field(type = FieldType.Boolean, name = "anomaly")
    private Boolean anomaly;

    @Field(type = FieldType.Text, name = "anomalyReasons")
    private List<String> anomalyReasons;

    @Field(type = FieldType.Double, name = "anomalyScore")
    private Double anomalyScore;

    public static class LogDocumentDeserializer extends JsonDeserializer<LogDocument> {
        @Override
        public LogDocument deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
            LogDocument doc = new LogDocument();
            JsonNode rootNode = p.readValueAsTree();

            ObjectNode node;
            if (rootNode.isArray()) {
                if (rootNode.size() > 0) {
                    node = (ObjectNode) rootNode.get(0);
                } else {
                    return doc;
                }
            } else if (rootNode.isObject()) {
                node = (ObjectNode) rootNode;
            } else {
                return doc;
            }

            doc.setId(getStringValue(node, "id"));
            doc.setContent(getStringValue(node, "content"));
            doc.setSeverity(getStringValue(node, "severity"));
            doc.setProtocol(getStringValue(node, "protocol"));
            doc.setSource(getStringValue(node, "source"));
            doc.setTaskId(getStringValue(node, "taskId"));
            doc.setAnomalyScore(getDoubleValue(node, "anomalyScore"));

            String anomalyStr = getStringValue(node, "anomaly");
            if (anomalyStr != null) {
                doc.setAnomaly(Boolean.parseBoolean(anomalyStr));
            }

            String timestampStr = getStringValue(node, "@timestamp");
            if (timestampStr == null) {
                timestampStr = getStringValue(node, "timestamp");
            }
            if (timestampStr != null) {
                doc.setTimestamp(parseTimestamp(timestampStr));
            }

            doc.setAnomalyReasons(extractAnomalyReasons(node));

            return doc;
        }

        private String getStringValue(ObjectNode node, String fieldName) {
            JsonNode fieldNode = node.get(fieldName);
            if (fieldNode == null) {
                return null;
            }
            if (fieldNode.isTextual()) {
                return fieldNode.asText();
            }
            if (fieldNode.isNumber()) {
                return fieldNode.asText();
            }
            if (fieldNode.isArray()) {
                return fieldNode.toString();
            }
            if (fieldNode.isObject()) {
                ObjectNode objNode = (ObjectNode) fieldNode;
                if (objNode.has("keyword")) {
                    return objNode.get("keyword").asText();
                }
                if (objNode.has("text")) {
                    return objNode.get("text").asText();
                }
                return objNode.toString();
            }
            return fieldNode.toString();
        }

        private Double getDoubleValue(ObjectNode node, String fieldName) {
            JsonNode fieldNode = node.get(fieldName);
            if (fieldNode == null) {
                return null;
            }
            if (fieldNode.isNumber()) {
                return fieldNode.asDouble();
            }
            if (fieldNode.isTextual()) {
                try {
                    return Double.parseDouble(fieldNode.asText());
                } catch (NumberFormatException e) {
                    return null;
                }
            }
            return null;
        }

        private List<String> extractAnomalyReasons(ObjectNode node) {
            List<String> reasons = new ArrayList<>();
            JsonNode reasonsNode = node.get("anomalyReasons");

            if (reasonsNode == null) {
                reasonsNode = node.get("anomaly_reasons");
            }

            if (reasonsNode != null) {
                if (reasonsNode.isArray()) {
                    for (JsonNode item : reasonsNode) {
                        if (item.isTextual()) {
                            reasons.add(item.asText());
                        } else if (item.isObject()) {
                            reasons.add(item.toString());
                        }
                    }
                } else if (reasonsNode.isTextual()) {
                    String text = reasonsNode.asText();
                    if (text.startsWith("[") && text.endsWith("]")) {
                        text = text.substring(1, text.length() - 1);
                        for (String part : text.split(",")) {
                            part = part.trim().replaceAll("^\"|\"$", "");
                            if (!part.isEmpty()) {
                                reasons.add(part);
                            }
                        }
                    } else if (!text.isEmpty()) {
                        reasons.add(text);
                    }
                }
            }

            JsonNode reasonsObj = node.get("anomalyReasonsObj");
            if (reasonsObj != null && reasonsObj.isArray()) {
                for (JsonNode item : reasonsObj) {
                    if (item.isTextual()) {
                        reasons.add(item.asText());
                    } else if (item.isObject() && item.has("reason")) {
                        reasons.add(item.get("reason").asText());
                    }
                }
            }

            return reasons;
        }

        private LocalDateTime parseTimestamp(String timestamp) {
            if (timestamp == null || timestamp.isEmpty()) {
                return null;
            }

            timestamp = timestamp.replaceAll("\"", "").trim();

            String[] formats = {
                "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
                "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
                "yyyy-MM-dd'T'HH:mm:ssXXX",
                "yyyy-MM-dd'T'HH:mm:ss'Z'",
                "yyyy-MM-dd'T'HH:mm:ss",
                "yyyy-MM-dd HH:mm:ss",
                "yyyy/MM/dd HH:mm:ss"
            };

            for (String format : formats) {
                try {
                    DateTimeFormatter formatter = DateTimeFormatter.ofPattern(format);
                    return LocalDateTime.parse(timestamp, formatter);
                } catch (Exception e) {
                }
            }

            try {
                long epochMilli = Long.parseLong(timestamp);
                return LocalDateTime.ofEpochSecond(epochMilli / 1000, 0, java.time.ZoneOffset.of("+8"));
            } catch (NumberFormatException e) {
            }

            return null;
        }
    }
}