package com.example.project.controller.domain;
import lombok.Data;
/** 个人资料接口只接收可编辑字段，忽略客户端 id/uid/role/password。 */
@Data
public class ProfileUpdate {
    private String email;
    private String avatar;
}
