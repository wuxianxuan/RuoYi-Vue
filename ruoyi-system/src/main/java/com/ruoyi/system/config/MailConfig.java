package com.ruoyi.system.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import java.util.Properties;

/**
 * 邮件发送配置
 *
 * 手动创建 JavaMailSender Bean（不依赖 Spring Boot 的 MailSenderAutoConfiguration），
 * 避免某些环境下自动装配未生效导致 Bean 缺失。SMTP 参数读取 application.yml 的 spring.mail。
 *
 * @author ruoyi
 */
@Configuration
public class MailConfig
{
    @Bean
    public JavaMailSender javaMailSender(
            @Value("${spring.mail.host}") String host,
            @Value("${spring.mail.port:25}") int port,
            @Value("${spring.mail.username}") String username,
            @Value("${spring.mail.password}") String password)
    {
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost(host);
        sender.setPort(port);
        sender.setUsername(username);
        sender.setPassword(password);
        sender.setDefaultEncoding("UTF-8");

        Properties props = sender.getJavaMailProperties();
        props.put("mail.smtp.auth", "true");
        props.put("mail.smtp.ssl.enable", "true");
        props.put("mail.smtp.connectiontimeout", "10000");
        props.put("mail.smtp.timeout", "10000");
        return sender;
    }
}
