package com.award.log.mapper;

import com.award.log.model.UserApiKey;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface UserApiKeyMapper {

    List<UserApiKey> selectByUserId(@Param("userId") Integer userId);

    List<UserApiKey> selectByPrefix(@Param("keyPrefix") String keyPrefix);

    UserApiKey selectById(@Param("id") Long id);

    int insert(UserApiKey apiKey);

    int updateKeyMaterial(@Param("id") Long id,
                          @Param("keyPrefix") String keyPrefix,
                          @Param("keyHash") String keyHash,
                          @Param("updatedAt") LocalDateTime updatedAt);

    int revoke(@Param("id") Long id,
               @Param("revokedAt") LocalDateTime revokedAt,
               @Param("updatedAt") LocalDateTime updatedAt);

    int touchLastUsed(@Param("id") Long id,
                      @Param("lastUsedAt") LocalDateTime lastUsedAt,
                      @Param("updatedAt") LocalDateTime updatedAt);

    long countActiveByUserId(@Param("userId") Integer userId);
}
