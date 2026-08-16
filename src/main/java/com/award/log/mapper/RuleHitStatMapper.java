package com.award.log.mapper;

import com.award.log.model.RuleHitStat;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface RuleHitStatMapper {
    int insert(RuleHitStat stat);
    List<RuleHitStat> selectLatest(int limit);
}
