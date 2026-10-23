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
* 考勤规则
* </p>
*
* @author
* @since 2025-01-23
*/
@Getter
@Setter
@ApiModel(value = "Rules对象", description = "考勤规则")
public class Rules implements Serializable {

private static final long serialVersionUID = 1L;

    // 编号
    @ApiModelProperty("编号")
    @Alias("编号")
    @TableId(value = "id", type = IdType.AUTO)
    private Integer id;


    // 规则名称
    @ApiModelProperty("规则名称")
    @Alias("规则名称")
    private String name;


    // 具体时间
    @ApiModelProperty("具体时间")
    @Alias("具体时间")
    private String time;



}
