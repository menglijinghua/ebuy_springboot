package com.lcvc.ebuy_springboot.web.interceptor;

import com.lcvc.ebuy_springboot.model.base.Constant;
import com.lcvc.ebuy_springboot.model.base.JsonCode;
import com.lcvc.ebuy_springboot.model.exception.MyFormException;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.HashMap;
import java.util.Map;

/**
 * @author ljy
 * 20190819
 * 用于处理本服务端所有控制层的异常信息并返回前端
 * 说明：使用此异常类，应按照把业务层异常往控制层抛，并且在控制层不轻易做异常处理，都交由此类处理
 */
@ControllerAdvice
@ResponseBody
public class MyExceptionAdvice {
    public static final Log log= LogFactory.getLog(MyExceptionAdvice.class);

    @ExceptionHandler
    public Map<String, Object> myFormException(MyFormException e) {
        Map<String, Object> map=new HashMap<String, Object>();
        map.put(Constant.JSON_MESSAGE, e.getMessage());
        map.put(Constant.JSON_CODE, JsonCode.ERROR.getValue());//返回错误信息
        //log.error("前端提交异常", e.getMessage());
        return map;
    }

    @ExceptionHandler
    public Map<String, Object> unknownException(Exception e) {
        Map<String, Object> map=new HashMap<String, Object>();
        map.put(Constant.JSON_MESSAGE, "未知异常："+e.getMessage());
        map.put(Constant.JSON_CODE, JsonCode.ERROR.getValue());//返回错误信息
        //未知异常一般是计划外的，需要重点处理，比如记录下日志，或是自动发送错误信息邮件给技术部
        //log.error("前端提交异常", e.getMessage());
        return map;
    }
}
