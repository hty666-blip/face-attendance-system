package com.example.project.exception;

import cn.dev33.satoken.exception.NotPermissionException;
import cn.dev33.satoken.exception.SaTokenException;
import cn.hutool.core.util.StrUtil;
import com.example.project.common.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public Result malformedJson(HttpMessageNotReadableException e) {
        // Jackson 异常可携带原请求片段；人脸图片、模板和密码不进入异常日志。
        log.warn("请求JSON格式或字段类型无效");
        return Result.error("400", "请求格式无效，请检查输入后重试");
    }

    @ExceptionHandler(DuplicateKeyException.class)
public Result duplicateKeyException(DuplicateKeyException e) {
        log.error("数据添加错误", e);
        return Result.error("500", "数据重复");
    }

    @ExceptionHandler(value = NotPermissionException.class)
public Result notPermissionException(NotPermissionException e) {
        log.error("权限验证错误", e);
        return Result.error("403", "无权限");
    }

    @ExceptionHandler(value = SaTokenException.class)
public Result notLoginException(SaTokenException e) {
        log.error("权限验证错误", e);
        return Result.error("401", "请登录");
    }

    @ExceptionHandler(value = MethodArgumentNotValidException.class)
public Result methodArgumentNotValidException(MethodArgumentNotValidException e) {
        String msg;
        try {
            msg = e.getBindingResult().getAllErrors().get(0).getDefaultMessage();
        } catch (Exception be) {
            msg = "";
        }
        log.warn("参数校验错误", e);
        return Result.error(msg);
    }

    @ExceptionHandler(value = ServiceException.class)
public Result serviceExceptionError(ServiceException e) {
        String code = e.getCode();
        if (StrUtil.isNotBlank(code)) {
            return Result.error(code, e.getMessage());
        }
        return Result.error(e.getMessage());
    }


    @ExceptionHandler(value = Exception.class)
public Result exceptionError(Exception e) {
        log.error("未知错误", e);
        return Result.error("未知错误");
    }

}
