package com.award.log.service.impl;

import com.award.log.service.KafkaMonitorService;
import org.apache.kafka.clients.admin.*;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.Node;
import org.apache.kafka.common.TopicPartition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.TimeUnit;

@Service
@ConditionalOnProperty(prefix = "award.middleware", name = "kafka", havingValue = "true")
public class KafkaMonitorServiceImpl implements KafkaMonitorService {

    @Value("${spring.kafka.bootstrap-servers:localhost:9092}")
    private String bootstrapServers;

    @Override
    public Map<String, Object> snapshot() {
        Map<String, Object> result = new HashMap<>();
        List<Map<String, Object>> topics = new ArrayList<>();
        List<Map<String, Object>> groups = new ArrayList<>();
        List<Map<String, Object>> brokers = new ArrayList<>();

        Properties props = new Properties();
        props.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, "5000");
        props.put(AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, "5000");

        boolean online = true;
        String error = "";
        try (AdminClient admin = AdminClient.create(props)) {
            Collection<Node> nodes = admin.describeCluster().nodes().get(5, TimeUnit.SECONDS);
            for (Node node : nodes) {
                Map<String, Object> broker = new HashMap<>();
                broker.put("id", node.id());
                broker.put("host", node.host());
                broker.put("port", node.port());
                brokers.add(broker);
            }

            Set<String> topicNames = admin.listTopics().names().get(5, TimeUnit.SECONDS);
            Map<String, TopicDescription> topicDescriptions = admin.describeTopics(topicNames).allTopicNames().get(5, TimeUnit.SECONDS);
            for (Map.Entry<String, TopicDescription> entry : topicDescriptions.entrySet()) {
                TopicDescription td = entry.getValue();
                Map<String, Object> topic = new HashMap<>();
                topic.put("name", entry.getKey());
                topic.put("partitions", td.partitions().size());
                topic.put("replicationFactor", td.partitions().isEmpty() ? 0 : td.partitions().get(0).replicas().size());
                topic.put("status", "ONLINE");
                topics.add(topic);
            }

            Collection<ConsumerGroupListing> groupListings = admin.listConsumerGroups().all().get(5, TimeUnit.SECONDS);
            for (ConsumerGroupListing listing : groupListings) {
                String groupId = listing.groupId();
                Map<String, Object> group = new HashMap<>();
                group.put("groupId", groupId);
                try {
                    ConsumerGroupDescription desc = admin.describeConsumerGroups(Collections.singletonList(groupId))
                            .describedGroups().get(groupId).get(5, TimeUnit.SECONDS);

                    int members = desc.members() == null ? 0 : desc.members().size();
                    group.put("members", members);
                    group.put("status", String.valueOf(desc.state()));

                    Map<TopicPartition, OffsetAndMetadata> committed = admin.listConsumerGroupOffsets(groupId)
                            .partitionsToOffsetAndMetadata().get(5, TimeUnit.SECONDS);
                    Map<TopicPartition, OffsetSpec> requestLatest = new HashMap<>();
                    committed.keySet().forEach(tp -> requestLatest.put(tp, OffsetSpec.latest()));

                    long totalLag = 0L;
                    if (!requestLatest.isEmpty()) {
                        Map<TopicPartition, ListOffsetsResult.ListOffsetsResultInfo> latestOffsets =
                                admin.listOffsets(requestLatest).all().get(5, TimeUnit.SECONDS);
                        for (Map.Entry<TopicPartition, OffsetAndMetadata> c : committed.entrySet()) {
                            long committedOffset = c.getValue() == null ? 0L : c.getValue().offset();
                            long endOffset = latestOffsets.containsKey(c.getKey()) ? latestOffsets.get(c.getKey()).offset() : 0L;
                            totalLag += Math.max(0, endOffset - committedOffset);
                        }
                    }
                    group.put("lag", totalLag);
                } catch (Exception ex) {
                    group.put("members", 0);
                    group.put("status", "UNKNOWN");
                    group.put("lag", 0L);
                    group.put("error", ex.getMessage());
                }
                groups.add(group);
            }
        } catch (Exception ex) {
            online = false;
            error = ex.getMessage();
        }

        result.put("online", online);
        result.put("error", error);
        result.put("bootstrapServers", bootstrapServers);
        result.put("brokers", brokers);
        result.put("topics", topics);
        result.put("consumerGroups", groups);
        result.put("topicCount", topics.size());
        result.put("groupCount", groups.size());
        long totalLag = groups.stream().mapToLong(g -> ((Number) g.getOrDefault("lag", 0L)).longValue()).sum();
        result.put("totalLag", totalLag);
        result.put("timestamp", System.currentTimeMillis());
        return result;
    }
}
