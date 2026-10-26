package com.example.project.report;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import javax.annotation.Resource;

@Configuration @EnableScheduling @Slf4j
@ConditionalOnProperty(prefix="attendance.report",name="scheduling-enabled",havingValue="true",matchIfMissing=true)
public class ReportExportScheduler {
    @Resource private ReportExportWorker worker;
    @Scheduled(initialDelay=1000,fixedDelay=15000)
    public void scan() {
        try {worker.runOnce();} catch(Exception e) {log.warn("Report export scan failed with {}",e.getClass().getSimpleName());}
    }
}
