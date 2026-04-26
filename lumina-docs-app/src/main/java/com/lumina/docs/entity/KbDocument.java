package com.lumina.docs.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Builder;
import lombok.Data;

import java.util.Date;

@Data
@Builder
@TableName("kb_document")
public class KbDocument {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String docName;
    private String esIndexName;
    private String esParentId; // 关联轮子里返回的 parentId
    private String filePath;
    private Integer status;
    private Date createTime;
}