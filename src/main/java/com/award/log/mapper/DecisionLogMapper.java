package com.award.log.mapper;

import com.award.log.model.DecisionLog;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

@Mapper
public interface DecisionLogMapper {
    int insert(DecisionLog log);

    DecisionLog selectByDecisionId(String decisionId);

    List<DecisionLog> selectByFilter(@Param("engineType") String engineType,
                                     @Param("startTime") String startTime,
                                     @Param("endTime") String endTime,
                                     @Param("result") Integer result,
                                     @Param("offset") int offset,
                                     @Param("pageSize") int pageSize);

    List<Map<String, Object>> selectOfflinePairs(@Param("startTime") String startTime,
                                                 @Param("endTime") String endTime);
}
