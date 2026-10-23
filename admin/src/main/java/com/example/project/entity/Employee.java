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
* 员工信息
* </p>
*
* @author
* @since 2025-01-23
*/
@Getter
@Setter
@ApiModel(value = "Employee对象", description = "员工信息")
public class Employee implements Serializable {

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


    // 员工姓名
    @ApiModelProperty("员工姓名")
    @Alias("员工姓名")
    private String name;


    // 所属用户
    @ApiModelProperty("所属用户")
    @Alias("所属用户")
    private Integer userId;


    // 所属部门
    @ApiModelProperty("所属部门")
    @Alias("所属部门")
    private Integer deptId;


    // 手机号码
    @ApiModelProperty("手机号码")
    @Alias("手机号码")
    private String phone;


    // 职位
    @ApiModelProperty("职位")
    @Alias("职位")
    private String title;



}
