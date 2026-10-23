package com.example.project.entity;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import java.io.Serializable;
import cn.hutool.core.annotation.Alias;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import org.springframework.format.annotation.DateTimeFormat;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.example.project.common.LDTConfig;
import java.util.List;
import lombok.Getter;
import lombok.Setter;

/**
* <p>
* 部门主管
* </p>
*
* @author
* @since 2025-01-23
*/
@Getter
@Setter
@ApiModel(value = "Manager对象", description = "部门主管")
public class Manager implements Serializable {

    @com.baomidou.mybatisplus.annotation.TableField(exist = false)
    @com.fasterxml.jackson.annotation.JsonProperty(access = com.fasterxml.jackson.annotation.JsonProperty.Access.WRITE_ONLY)
    private String initialPassword;

private static final long serialVersionUID = 1L;

    // 编号
    @ApiModelProperty("编号")
    @Alias("编号")
    @TableId(value = "id", type = IdType.AUTO)
    private Integer id;


    // 登录账户
    @ApiModelProperty("登录账户")
    @Alias("登录账户")
    private String username;


    // 主管姓名
    @ApiModelProperty("主管姓名")
    @Alias("主管姓名")
    private String name;


    // 所属用户
    @ApiModelProperty("所属用户")
    @Alias("所属用户")
    private Integer userId;


    // 手机号码
    @ApiModelProperty("手机号码")
    @Alias("手机号码")
    private String phone;


    // 所属部门
    @ApiModelProperty("所属部门")
    @Alias("所属部门")
    private Integer deptId;


    // 职位
    @ApiModelProperty("职位")
    @Alias("职位")
    private String title;



}
