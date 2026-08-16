package com.award.log.mapper;

import com.award.log.model.EngineOfflineMetric;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface EngineOfflineMetricMapper {
    int insert(EngineOfflineMetric metric);
    List<EngineOfflineMetric> selectLatest(int limit);
}
