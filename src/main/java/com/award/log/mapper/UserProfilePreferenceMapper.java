package com.award.log.mapper;

import com.award.log.model.UserProfilePreference;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface UserProfilePreferenceMapper {

    UserProfilePreference selectByUserId(@Param("userId") Integer userId);

    int upsert(UserProfilePreference preference);
}
