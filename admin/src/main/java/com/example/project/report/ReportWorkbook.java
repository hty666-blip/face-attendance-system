package com.example.project.report;

import com.example.project.exception.ServiceException;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;
import java.io.*;
import java.util.*;

/** 仅消费月报的白名单结果，用户文本始终写 STRING 单元格。 */
@Component
public class ReportWorkbook {
    public static final int MAX_BYTES=8*1024*1024;
    private static final String[] METRICS={"workingDays","elapsedWorkingDays","normalDays","lateDays","earlyDays","missingDays","leaveDays","lateMinutes","earlyMinutes","restrictedDays"};
    private static final String[] LABELS={"工作日","已到日期工作日","正常天数","迟到天数","早退天数","缺卡天数","请假天数","迟到分钟","早退分钟","受限天数"};

    @SuppressWarnings("unchecked")
    public byte[] create(Map<String,Object> report) {
        try(XSSFWorkbook book=new XSSFWorkbook(); ByteArrayOutputStream bytes=new ByteArrayOutputStream()) {
            CellStyle title=book.createCellStyle();Font font=book.createFont();font.setBold(true);title.setFont(font);
            Sheet notes=book.createSheet("说明");
            line(notes,title,"项目","内容");
            line(notes,null,"报表月份",report.get("month"));line(notes,null,"范围",report.get("scope"));
            line(notes,null,"部门",report.get("departmentName"));line(notes,null,"生成时间（北京时间）",report.get("asOf"));
            line(notes,null,"计算版本",report.get("calculationVersion"));line(notes,null,"尚未结算",report.get("unsettled"));
            line(notes,null,"统计说明","当前可见日结果；含今天或未来日期时尚未结算，不能用于工资结算。");
            line(notes,null,"部门口径","以当前在职员工档案为人员基准，再过滤历史日归属；不是完整历史花名册。");
            line(notes,null,"历史与受限","LEGACY、RESTRICTED、UPCOMING、IN_PROGRESS 保持原标记；未知记录不补零为正常。");
            Map<String,Object> stats=(Map<String,Object>)report.get("statistics");
            line(notes,null,"人数",stats.get("peopleCount"));
            for(int i=0;i<METRICS.length;i++) line(notes,null,LABELS[i],stats.get(METRICS[i]));
            Sheet people=book.createSheet("人员汇总");
            List<Object> header=new ArrayList<>(Arrays.asList("人员编号","用户名","姓名"));header.addAll(Arrays.asList(LABELS));line(people,title,header.toArray());
            for(Map<String,Object> person:(List<Map<String,Object>>)report.get("people")) {
                List<Object> values=new ArrayList<>(Arrays.asList(person.get("userId"),person.get("username"),person.get("name")));
                Map<String,Object> counts=(Map<String,Object>)person.get("statistics");
                for(String key:METRICS) values.add(counts.get(key));line(people,null,values.toArray());
            }
            Sheet days=book.createSheet("每日明细");
            line(days,title,"人员编号","姓名","日期","状态","工作日","规则编号","上班时间","下班时间","宽限分钟","签到发生时间","签到来源","签到写入时间","签退发生时间","签退来源","签退写入时间","迟到分钟","早退分钟","缺签到","缺签退","请假天数","待审批条数");
            for(Map<String,Object> day:(List<Map<String,Object>>)report.get("days")) {
                Map<String,Object> in=(Map<String,Object>)day.get("checkIn"),out=(Map<String,Object>)day.get("checkOut");
                line(days,null,day.get("userId"),day.get("name"),day.get("businessDate"),day.get("status"),day.get("workingDay"),day.get("policyId"),day.get("startTime"),day.get("endTime"),day.get("graceMinutes"),
                        field(in,"occurredAt"),field(in,"source"),field(in,"createTime"),field(out,"occurredAt"),field(out,"source"),field(out,"createTime"),
                        day.get("lateMinutes"),day.get("earlyMinutes"),day.get("missingIn"),day.get("missingOut"),day.get("leaveDays"),day.get("pendingCount"));
            }
            for(Sheet sheet:book) {sheet.createFreezePane(0,1);for(int c=0;c<sheet.getRow(0).getLastCellNum();c++) sheet.setColumnWidth(c,(sheet==notes?(c==0?25:90):18)*256);}
            book.write(new FilterOutputStream(bytes) {
                private void capacity(int n) {if(bytes.size()+n>MAX_BYTES) throw new ServiceException("422","报表超过8MiB，请缩小导出范围");}
                @Override public void write(int b) {capacity(1);bytes.write(b);}
                @Override public void write(byte[] b,int off,int len) {capacity(len);bytes.write(b,off,len);}
            });
            return bytes.toByteArray();
        } catch(IOException e) {throw new ServiceException("报表文件生成失败",e);}
    }
    private Object field(Map<String,Object> event,String key) {return event==null?null:event.get(key);}
    private void line(Sheet sheet,CellStyle style,Object... values) {
        Row row=sheet.createRow(sheet.getPhysicalNumberOfRows());
        for(int i=0;i<values.length;i++) {
            Cell cell=row.createCell(i);Object value=values[i];
            if(value instanceof Number) cell.setCellValue(((Number)value).doubleValue());
            else if(value instanceof Boolean) cell.setCellValue((Boolean)value);
            else if(value!=null) cell.setCellValue(String.valueOf(value));
            if(style!=null) cell.setCellStyle(style);
        }
    }
}
