package com.award.log.decision;

import com.award.log.collector.model.RawLogEvent;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class RfFeatureVectorExtTest {

    @Test
    void shouldBuild25Features() {
        RawLogEvent event = new RawLogEvent();
        event.setLevel("ERROR");
        event.setContent("java.lang.RuntimeException timeout at com.demo.Service");
        event.setEventTime(System.currentTimeMillis() - 10);
        event.setIngestTime(System.currentTimeMillis());

        DecisionInput input = DecisionInput.builder()
                .event(event)
                .template("RuntimeException timeout")
                .errorRate1m(0.4)
                .error1m(12)
                .total1m(40)
                .build();

        float[] arr = RfFeatureVectorExt.fromDecisionInputExt(input).toArray();
        Assertions.assertEquals(25, arr.length);
        Assertions.assertTrue(arr[4] > 0);
    }
}
