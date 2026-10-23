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
* 请假申请
* </p>
*
* @author
* @since 2025-01-23
*/
@Getter
@Setter
@ApiModel(value = "Leaveapply对象", description = "请假申请")
public class Leaveapply implements Serializable {

private static final long serialVersionUID = 1L;

    // 编号
    @ApiModelProperty("编号")
    @Alias("编号")
    @TableId(value = "id", type = IdType.AUTO)
    private Integer id;


    // 请假员工
    @ApiModelProperty("请假员工")
    @Alias("请假员工")
    private Integer userId;


    // 类型,事假|病假|婚假|丧假
    @ApiModelProperty("类型,事假|病假|婚假|丧假")
    @Alias("类型,事假|病假|婚假|丧假")
    private String typeRadio;


    // 请假时长
    @ApiModelProperty("请假时长")
    @Alias("请假时长")
    private String periods;


    // 请假原因
    @ApiModelProperty("请假原因")
    @Alias("请假原因")
    private String content;
    public String getContent() { return org.jsoup.Jsoup.clean(content == null ? "" : content, org.jsoup.safety.Safelist.basicWithImages()); }
    public void setContent(String value) { this.content = org.jsoup.Jsoup.clean(value == null ? "" : value, org.jsoup.safety.Safelist.basicWithImages()); }


    // 申请时间
    @ApiModelProperty("申请时间")
    @Alias("申请时间")
    @TableField(fill = FieldFill.INSERT)
    @JsonFormat(shape=JsonFormat.Shape.STRING, pattern="yyyy-MM-dd HH:mm:ss")
    @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createTime;


    // 状态,审核中|通过|不通过
    @ApiModelProperty("状态,审核中|通过|不通过")
    @Alias("状态,审核中|通过|不通过")
    private String stateRadio;


    // 审核说明
    @ApiModelProperty("审核说明")
    @Alias("审核说明")
    private String remarks;



}
