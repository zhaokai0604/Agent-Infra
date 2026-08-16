package com.award.log.mapper;

import com.award.log.model.LogTemplateRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface LogTemplateMapper {
    int insert(LogTemplateRecord record);

    int update(LogTemplateRecord record);

    int deleteByTemplateId(String templateId);

    LogTemplateRecord selectByTemplateId(String templateId);

    List<LogTemplateRecord> selectPage(@Param("offset") int offset, @Param("pageSize") int pageSize);
}
