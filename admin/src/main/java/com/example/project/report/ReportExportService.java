package com.example.project.report;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.project.attendance.AttendanceClock;
import com.example.project.entity.*;
import com.example.project.exception.ServiceException;
import com.example.project.mapper.*;
import com.example.project.security.AccessPolicy;
import com.example.project.utils.SessionUtils;
import lombok.Data;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import javax.annotation.Resource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;

/** 创建、领取、发布均使用短事务；身份校验不依赖后台线程的 HTTP 登录会话。 */
@Service
public class ReportExportService {
    @Resource private AttendanceExportJobMapper jobs;
    @Resource private UserMapper users;
    @Resource private MonthlyReportService reports;
    @Resource private AttendanceClock clock;
    @Resource private TransactionTemplate tx;
    @Value("${attendance.report.max-active-jobs:3}") private int maxActive;
    @Data public static class CreateInput {private String requestKey,month,scope;private Integer deptId;}

    public Map<String,Object> create(CreateInput input) {
        Integer actorId=SessionUtils.getUser().getId();
        CreateInput normalized=normalize(input);String hash=hash(normalized.getMonth()+"|"+normalized.getScope()+"|"+normalized.getDeptId());
        AttendanceExportJob job=tx.execute(status -> {
            if(users.lockActiveId(actorId)==null) AccessPolicy.deny();
            String scope=reports.scopeSignature(actorId,normalized.getMonth(),normalized.getScope(),normalized.getDeptId(),true);
            AttendanceExportJob previous=jobs.selectOne(new QueryWrapper<AttendanceExportJob>().eq("owner_id",actorId).eq("request_key",normalized.getRequestKey()));
            if(previous!=null) {if(!hash.equals(previous.getRequestHash())) conflict("同一请求编号不能用于不同的报表条件");return previous;}
            Long active=jobs.selectCount(new QueryWrapper<AttendanceExportJob>().eq("owner_id",actorId).in("state","QUEUED","RUNNING"));
            if(active>=Math.max(1,maxActive)) conflict("未完成导出任务已达上限，请等待完成或取消后重试");
            AttendanceExportJob row=new AttendanceExportJob();row.setOwnerId(actorId);row.setRequestKey(normalized.getRequestKey());row.setRequestHash(hash);
            row.setMonth(normalized.getMonth());row.setScope(normalized.getScope());row.setDeptId(normalized.getDeptId());row.setScopeSignature(scope);
            row.setState("QUEUED");row.setAttempts(0);row.setCreatedAt(clock.now());
            if(jobs.insert(row)!=1) throw new ServiceException("导出任务保存失败，请重试原请求");return row;
        });
        return metadata(job);
    }

    public Map<String,Object> list(int pageNum,int pageSize) {
        if(pageNum<1 || pageSize<1 || pageSize>100) invalid("分页参数无效");
        Integer owner=SessionUtils.getUser().getId();expireFiles();
        Page<AttendanceExportJob> page=jobs.selectPage(new Page<>(pageNum,pageSize),new QueryWrapper<AttendanceExportJob>().eq("owner_id",owner).orderByDesc("id"));
        List<Map<String,Object>> rows=new ArrayList<>();for(AttendanceExportJob row:page.getRecords()) rows.add(metadata(row));
        Map<String,Object> result=new LinkedHashMap<>();result.put("records",rows);result.put("total",page.getTotal());return result;
    }
    public Map<String,Object> detail(Long id) {expireFiles();return metadata(owned(id));}
    public Map<String,Object> cancel(Long id) {
        AttendanceExportJob row=owned(id);
        return tx.execute(status -> {
            jobs.update(null,new UpdateWrapper<AttendanceExportJob>().eq("id",row.getId()).eq("owner_id",row.getOwnerId()).in("state","QUEUED","RUNNING")
                    .set("state","CANCELLED").set("finished_at",clock.now()).set("lease_token",null).set("lease_until",null).set("file_content",null));
            AttendanceExportJob latest=jobs.selectById(id);
            if(!"CANCELLED".equals(latest.getState())) conflict("仅可取消排队或生成中的任务");return metadata(latest);
        });
    }
    public byte[] download(Long id) {
        expireFiles();AttendanceExportJob row=owned(id);LocalDateTime now=clock.now();
        if(!"READY".equals(row.getState()) || row.getExpiresAt()==null || !row.getExpiresAt().isAfter(now)) conflict("文件尚未就绪或已过期，请重新创建导出任务");
        Map<String,Object> current=reports.build(row.getOwnerId(),row.getMonth(),row.getScope(),row.getDeptId(),true);
        requireSignatures(row,current);
        // 再次读取状态，避免取消/过期清理与文件读取交错返回无效内容。
        byte[] content=jobs.content(row.getId(),row.getOwnerId());
        AttendanceExportJob latest=jobs.selectById(row.getId());
        if(content==null || latest==null || !"READY".equals(latest.getState()) || !latest.getExpiresAt().isAfter(clock.now())) conflict("文件已过期，请重新创建导出任务");
        return content;
    }

    /** 每次启动/扫描均先恢复租约，旧工作者只有匹配当前 token 才能提交。 */
    public void recoverAndExpire() {
        tx.executeWithoutResult(status -> {
            LocalDateTime now=clock.now();
            jobs.update(null,new UpdateWrapper<AttendanceExportJob>().eq("state","RUNNING").le("lease_until",now).ge("attempts",3)
                    .set("state","FAILED").set("finished_at",now).set("lease_token",null).set("lease_until",null)
                    .set("error_code","RETRY_EXHAUSTED").set("error_message","生成任务多次超时，请重新创建"));
            jobs.update(null,new UpdateWrapper<AttendanceExportJob>().eq("state","RUNNING").le("lease_until",now).lt("attempts",3)
                    .set("state","QUEUED").set("lease_token",null).set("lease_until",null)
                    .set("error_code","LEASE_RECOVERED").set("error_message","上次生成未完成，已重新排队"));
            expireFiles();
        });
    }
    public AttendanceExportJob claimNext() {
        return tx.execute(status -> {
            AttendanceExportJob row=jobs.selectOne(new QueryWrapper<AttendanceExportJob>().eq("state","QUEUED").lt("attempts",3).orderByAsc("id").last("LIMIT 1"));
            if(row==null) return null;
            LocalDateTime now=clock.now();String token=UUID.randomUUID().toString();
            int changed=jobs.update(null,new UpdateWrapper<AttendanceExportJob>().eq("id",row.getId()).eq("state","QUEUED").eq("attempts",row.getAttempts())
                    .set("state","RUNNING").set("attempts",row.getAttempts()+1).set("lease_token",token).set("lease_until",now.plusMinutes(5))
                    .set("started_at",now).set("error_code",null).set("error_message",null));
            if(changed!=1) return null;
            row.setState("RUNNING");row.setAttempts(row.getAttempts()+1);row.setLeaseToken(token);row.setLeaseUntil(now.plusMinutes(5));row.setStartedAt(now);return row;
        });
    }
    public void verifyScope(AttendanceExportJob row) {
        String current=reports.scopeSignature(row.getOwnerId(),row.getMonth(),row.getScope(),row.getDeptId(),true);
        if(!Objects.equals(row.getScopeSignature(),current)) conflict("报表人员范围已变化，请重新创建任务");
    }
    public Map<String,Object> build(AttendanceExportJob row) {
        verifyScope(row);Map<String,Object> report=reports.build(row.getOwnerId(),row.getMonth(),row.getScope(),row.getDeptId(),true);
        if(!Objects.equals(row.getScopeSignature(),report.get("scopeSignature"))) conflict("报表人员范围已变化，请重新创建任务");return report;
    }
    public boolean publish(AttendanceExportJob row,Map<String,Object> generated,byte[] content) {
        if(content==null || content.length==0 || content.length>ReportWorkbook.MAX_BYTES) invalid("报表文件为空或超过8MiB，请缩小范围重建");
        Map<String,Object> current=reports.build(row.getOwnerId(),row.getMonth(),row.getScope(),row.getDeptId(),true);
        requireSignatures(row,current);
        if(!Objects.equals(generated.get("accessSignature"),current.get("accessSignature"))) conflict("报表每日可见范围已变化，请重新创建任务");
        return tx.execute(status -> {
            LocalDateTime now=clock.now();
            return jobs.update(null,lease(row).gt("lease_until",now).set("state","READY").set("file_content",content)
                    .set("access_signature",String.valueOf(generated.get("accessSignature"))).set("finished_at",now).set("expires_at",now.plusHours(24))
                    .set("lease_token",null).set("lease_until",null).set("error_code",null).set("error_message",null))==1;
        });
    }
    public void fail(AttendanceExportJob row,String code,String message,boolean retryable) {
        tx.executeWithoutResult(status -> {
            boolean retry=retryable && row.getAttempts()<3;UpdateWrapper<AttendanceExportJob> update=lease(row).set("state",retry?"QUEUED":"FAILED")
                    .set("lease_token",null).set("lease_until",null).set("error_code",code).set("error_message",message).set("file_content",null);
            if(!retry) update.set("finished_at",clock.now());jobs.update(null,update);
        });
    }
    private UpdateWrapper<AttendanceExportJob> lease(AttendanceExportJob row) {
        return new UpdateWrapper<AttendanceExportJob>().eq("id",row.getId()).eq("state","RUNNING").eq("lease_token",row.getLeaseToken());
    }
    private void expireFiles() {
        jobs.update(null,new UpdateWrapper<AttendanceExportJob>().eq("state","READY").le("expires_at",clock.now()).set("state","EXPIRED").set("file_content",null));
    }
    private void requireSignatures(AttendanceExportJob row,Map<String,Object> current) {
        if(!Objects.equals(row.getScopeSignature(),current.get("scopeSignature")) || (row.getAccessSignature()!=null && !Objects.equals(row.getAccessSignature(),current.get("accessSignature"))))
            conflict("报表可见范围已变化，请重新创建任务");
    }
    private AttendanceExportJob owned(Long id) {
        Integer owner=SessionUtils.getUser().getId();AttendanceExportJob row=id==null?null:jobs.selectById(id);
        if(row==null) throw new ServiceException("404","导出任务不存在");if(!owner.equals(row.getOwnerId())) AccessPolicy.deny();return row;
    }
    private Map<String,Object> metadata(AttendanceExportJob row) {
        Map<String,Object> result=new LinkedHashMap<>();result.put("id",row.getId());result.put("month",row.getMonth());result.put("scope",row.getScope());result.put("deptId",row.getDeptId());
        result.put("state",row.getState());result.put("attempts",row.getAttempts());result.put("createdAt",row.getCreatedAt());result.put("startedAt",row.getStartedAt());
        result.put("finishedAt",row.getFinishedAt());result.put("expiresAt",row.getExpiresAt());result.put("errorCode",row.getErrorCode());result.put("errorMessage",row.getErrorMessage());return result;
    }
    private CreateInput normalize(CreateInput input) {
        if(input==null) invalid("请提供报表条件");CreateInput row=new CreateInput();
        String key=input.getRequestKey()==null?"":input.getRequestKey().trim();if(!key.matches("[A-Za-z0-9_-]{8,64}")) invalid("请求编号应为8至64位字母、数字、横线或下划线");
        row.setRequestKey(key);row.setMonth(input.getMonth()==null?"":input.getMonth().trim());row.setScope(input.getScope()==null?"":input.getScope().trim().toUpperCase(Locale.ROOT));row.setDeptId(input.getDeptId());
        if(!row.getMonth().matches("\\d{4}-\\d{2}")) invalid("月份格式应为YYYY-MM");try {YearMonth.parse(row.getMonth());}catch(Exception e){invalid("月份无效");}
        if(!Arrays.asList("SELF","DEPARTMENT").contains(row.getScope())) invalid("报表范围无效");
        if("SELF".equals(row.getScope()) && row.getDeptId()!=null) invalid("个人报表不应提交部门编号");
        if("DEPARTMENT".equals(row.getScope()) && (row.getDeptId()==null || row.getDeptId()<1)) invalid("请选择明确部门");return row;
    }
    private String hash(String text) {
        try {byte[] bytes=MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));StringBuilder s=new StringBuilder();for(byte b:bytes)s.append(String.format("%02x",b&255));return s.toString();}
        catch(Exception e){throw new IllegalStateException(e);}
    }
    private void invalid(String message){throw new ServiceException("422",message);}
    private void conflict(String message){throw new ServiceException("409",message);}
}
