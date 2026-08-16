package com.award.log.mcp.tools;

import com.award.log.mcp.McpToolResponses;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * TLS 证书有效期巡检（纯 Java SSLSocket，只读握手）。
 */
@Slf4j
@Component
public class SslCertTool {

    private static final Pattern HOST_PATTERN = Pattern.compile(
            "^[a-zA-Z0-9]([a-zA-Z0-9\\-]{0,61}[a-zA-Z0-9])?(\\.[a-zA-Z0-9]([a-zA-Z0-9\\-]{0,61}[a-zA-Z0-9])?)*$|^(\\d{1,3}\\.){3}\\d{1,3}$|^localhost$"
    );

    private final ObjectMapper objectMapper;

    public SslCertTool(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Tool(name = "checkSslCertificate",
            description = "检查目标主机 HTTPS/TLS 证书有效期与主题信息，用于证书到期预警（只读 TLS 握手）")
    public String checkSslCertificate(
            @ToolParam(description = "目标主机名或 IP（SNI）", required = true) String host,
            @ToolParam(description = "TLS 端口，默认 443", required = false) Integer port,
            @ToolParam(description = "连接超时毫秒，默认 5000，最大 20000", required = false) Integer timeoutMs
    ) throws JsonProcessingException {
        long start = System.currentTimeMillis();
        if (host == null || host.isBlank()) {
            return buildError("主机不能为空", start);
        }
        String targetHost = host.trim();
        if (!HOST_PATTERN.matcher(targetHost).matches()) {
            return buildError("主机名格式不合法", start);
        }
        int tlsPort = port != null ? Math.min(Math.max(port, 1), 65535) : 443;
        int timeout = timeoutMs != null ? Math.min(Math.max(timeoutMs, 1000), 20_000) : 5000;

        try {
            SSLSocketFactory factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
            try (SSLSocket socket = (SSLSocket) factory.createSocket()) {
                socket.connect(new java.net.InetSocketAddress(targetHost, tlsPort), timeout);
                SSLParameters params = socket.getSSLParameters();
                params.setServerNames(java.util.List.of(new SNIHostName(targetHost)));
                socket.setSSLParameters(params);
                socket.setSoTimeout(timeout);
                socket.startHandshake();

                java.security.cert.Certificate[] chain = socket.getSession().getPeerCertificates();
                if (chain.length == 0 || !(chain[0] instanceof X509Certificate cert)) {
                    return buildError("未获取到 X509 证书", start);
                }

                Instant notBefore = cert.getNotBefore().toInstant();
                Instant notAfter = cert.getNotAfter().toInstant();
                Instant now = Instant.now();
                long daysUntilExpiry = ChronoUnit.DAYS.between(now, notAfter);

                boolean expired = now.isAfter(notAfter);
                boolean expiringSoon = daysUntilExpiry >= 0 && daysUntilExpiry <= 30;

                Map<String, Object> data = new LinkedHashMap<>();
                data.put("host", targetHost);
                data.put("port", tlsPort);
                data.put("subject", cert.getSubjectX500Principal().getName());
                data.put("issuer", cert.getIssuerX500Principal().getName());
                data.put("notBefore", notBefore.toString());
                data.put("notAfter", notAfter.toString());
                data.put("daysUntilExpiry", daysUntilExpiry);
                data.put("expired", expired);
                data.put("expiringSoon", expiringSoon);
                data.put("serialNumber", cert.getSerialNumber().toString(16));

                long duration = System.currentTimeMillis() - start;
                log.info("SSL 证书检查 {}:{} 剩余 {} 天", targetHost, tlsPort, daysUntilExpiry);
                String dataJson = objectMapper.writeValueAsString(data);
                boolean healthy = !expired && !expiringSoon;
                return McpToolResponses.successOrWarn(objectMapper, dataJson, duration, healthy);
            }
        } catch (Exception e) {
            log.warn("SSL 证书检查失败 {}:{} - {}", targetHost, tlsPort, e.getMessage());
            return buildError("TLS 握手失败: " + e.getMessage(), start);
        }
    }

    private String buildError(String msg, long start) throws JsonProcessingException {
        return McpToolResponses.error(objectMapper, msg, start);
    }
}
