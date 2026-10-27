package com.ruoyi.system.service.impl;

import com.ruoyi.system.service.IMailNotifyService;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

/**
 * 邮件通知实现
 *
 * @author ruoyi
 */
@Service
public class MailNotifyServiceImpl implements IMailNotifyService
{
    private static final Logger log = LoggerFactory.getLogger(MailNotifyServiceImpl.class);

    @Resource
    private JavaMailSender mailSender;

    /** 是否启用邮件推送 */
    @Value("${stock.notify.enabled:false}")
    private boolean enabled;

    /** 收件邮箱（多个用逗号分隔） */
    @Value("${stock.notify.to:}")
    private String to;

    /** 发件人（默认取 spring.mail.username） */
    @Value("${stock.notify.from:}")
    private String from;

    /** SMTP 账号（发件人为空时兜底用它） */
    @Value("${spring.mail.username:}")
    private String smtpUsername;

    /** 邮件主题前缀 */
    @Value("${stock.notify.subject:尾盘推荐}")
    private String subjectPrefix;

    @Override
    public void send(String subject, String content)
    {
        if (!enabled)
        {
            log.debug("邮件推送未启用，跳过");
            return;
        }
        if (to == null || to.trim().isEmpty())
        {
            log.warn("未配置收件邮箱 stock.notify.to，跳过邮件推送");
            return;
        }
        // 发件人为空时用 SMTP 账号兜底，避免 "Illegal address in string ''"
        String fromAddr = (from != null && !from.trim().isEmpty()) ? from.trim() : smtpUsername;
        try
        {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(fromAddr);
            message.setTo(to.split(","));
            message.setSubject(subject);
            message.setText(content);
            mailSender.send(message);
            log.info("邮件已发送: from={}, to={}, subject={}", fromAddr, to, subject);
        }
        catch (Exception e)
        {
            log.error("邮件发送失败: {}", e.getMessage(), e);
        }
    }
}
