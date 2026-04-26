package com.lumina.docs.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lumina.docs.entity.KbDocument;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface KbDocumentMapper extends BaseMapper<KbDocument> {
    // MyBatis-Plus 自带极其强大的 CRUD，无需手写单表 SQL！
}