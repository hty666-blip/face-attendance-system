package com.example.project.report;

import com.example.project.entity.AttendanceExportJob;
import com.example.project.exception.ServiceException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import javax.annotation.Resource;
import java.util.Map;

@Service @Slf4j
public class ReportExportWorker {
    @Resource private ReportExportService exports;
    @Resource private ReportWorkbook workbook;
    /** 每轮最多一个长任务；调度串行，其他实例由数据库 CAS 互斥领取。 */
    public boolean runOnce() {
        exports.recoverAndExpire();AttendanceExportJob job=exports.claimNext();if(job==null)return false;
        try {Map<String,Object> report=exports.build(job);byte[] bytes=workbook.create(report);exports.publish(job,report,bytes);}
        catch(ServiceException e) {
            String code="403".equals(e.getCode()) || "401".equals(e.getCode())?"ACCESS_CHANGED":"409".equals(e.getCode())?"SCOPE_CHANGED":"INVALID_REPORT";
            exports.fail(job,code,"ACCESS_CHANGED".equals(code)?"账号或导出权限已变化，请重新登录后确认":"SCOPE_CHANGED".equals(code)?"报表可见范围已变化，请重新创建任务":"报表条件或文件大小不符合要求，请调整后重建",false);
        } catch(Exception e) {
            // 错误元数据不保存 SQL、配置、人员原文或异常堆栈。
            log.warn("Report export {} failed with {}",job.getId(),e.getClass().getSimpleName());
            exports.fail(job,"GENERATION_FAILED","生成暂时失败，系统将有限重试；达到上限后请重新创建",true);
        }
        return true;
    }
}
