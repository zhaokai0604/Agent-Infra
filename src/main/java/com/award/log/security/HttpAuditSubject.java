package com.award.log.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * 从当前 HTTP 会话解析操作者，用于运维审计落库。
 */
@Component
public class HttpAuditSubject {

    public String currentOperatorId() {
        try {
            ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attrs == null) {
                return null;
            }
            HttpServletRequest request = attrs.getRequest();
            Object requestUserId = request.getAttribute(RequestUserResolver.REQ_USER_ID);
            if (requestUserId != null) {
                return String.valueOf(requestUserId);
            }
            HttpSession session = request.getSession(false);
            if (session == null) {
                return null;
            }
            Object uid = session.getAttribute(AuthInterceptor.SESSION_USER_ID);
            return uid == null ? null : String.valueOf(uid);
        } catch (Exception e) {
            return null;
        }
    }
}
