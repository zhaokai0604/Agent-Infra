package com.award.log.mapper;

import com.award.log.model.ModelEvaluation;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface ModelEvaluationMapper {
    int insert(ModelEvaluation evaluation);

    List<ModelEvaluation> selectLatest(int limit);
}
