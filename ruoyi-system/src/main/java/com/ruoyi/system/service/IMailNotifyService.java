package com.ruoyi.system.service;

/**
 * 邮件通知 Service
 *
 * 把推荐结果通过邮件推送到手机（配合手机邮箱客户端即时查看）。
 * 依赖 spring-boot-starter-mail，SMTP 账号配置在 application.yml 的 spring.mail 下。
 */
public interface IMailNotifyService
{
    /**
     * 发送一封纯文本邮件
     *
     * @param subject 邮件主题
     * @param content 邮件正文
     */
    void send(String subject, String content);
}
