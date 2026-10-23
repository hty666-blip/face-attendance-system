package com.example.project.entity;
import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import java.io.Serializable;
import java.time.LocalDateTime;
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
* 考勤记录
* </p>
*
* @author
* @since 2025-01-23
*/
@Getter
@Setter
@ApiModel(value = "Records对象", description = "考勤记录")
public class Records implements Serializable {

private static final long serialVersionUID = 1L;

    // 编号
    @ApiModelProperty("编号")
    @Alias("编号")
    @TableId(value = "id", type = IdType.AUTO)
    private Integer id;


    // 考勤员工
    @ApiModelProperty("考勤员工")
    @Alias("考勤员工")
    private Integer userId;


    // 打卡时间
    @ApiModelProperty("打卡时间")
    @Alias("打卡时间")
    @TableField(fill = FieldFill.INSERT)
    @JsonFormat(shape=JsonFormat.Shape.STRING, pattern="yyyy-MM-dd HH:mm:ss")
    @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createTime;


    // 打卡地点
    @ApiModelProperty("打卡地点")
    @Alias("打卡地点")
    private String address;


    // 状态,迟到|早退
    @ApiModelProperty("状态,迟到|早退")
    @Alias("状态,迟到|早退")
    private String stateRadio;



}
