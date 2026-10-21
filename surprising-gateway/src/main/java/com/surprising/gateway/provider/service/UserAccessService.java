package com.surprising.gateway.provider.service;

import com.surprising.gateway.provider.auth.AuthModels.AdminSessionRevokeResponse;
import com.surprising.gateway.provider.auth.AuthModels.JwtPrincipal;
import com.surprising.gateway.provider.auth.GatewayLoginLogRepository;
import com.surprising.gateway.provider.auth.GatewayRefreshSessionRepository;
import com.surprising.gateway.provider.auth.GatewayUserAccessBlockRepository;
import com.surprising.gateway.provider.auth.SensitiveActionVerificationService;
import com.surprising.gateway.provider.auth.UserSecurityService;
import com.surprising.gateway.provider.exception.SecurityVerificationRequiredException;
import com.surprising.gateway.provider.model.UserDeviceStatus;
import com.surprising.gateway.provider.model.UserIpStatus;
import java.net.InetAddress;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 聚合本人安全验证、访问限制和会话撤销；本页封禁集合仅为响应派生值，不保存第二份账户状态。 */
@Service
public class UserAccessService {
    private final GatewayRefreshSessionRepository sessions;
    private final GatewayLoginLogRepository logins;
    private final GatewayUserAccessBlockRepository blocks;
    private final SensitiveActionVerificationService verification;
    private final UserSecurityService security;

    public UserAccessService(GatewayRefreshSessionRepository sessions, GatewayLoginLogRepository logins,
                             GatewayUserAccessBlockRepository blocks, SensitiveActionVerificationService verification,
                             UserSecurityService security) {
        this.sessions = sessions;
        this.logins = logins;
        this.blocks = blocks;
        this.verification = verification;
        this.security = security;
    }

    @Transactional(readOnly = true)
    public List<UserDeviceStatus> devices(JwtPrincipal actor, int limit, int offset) {
        String currentDevice = sessions.deviceIdForSession(actor.userId(), actor.sessionId()).orElse(null);
        var devices = sessions.devices(actor.userId(), limit, offset);
        var blocked = blocks.activeValues(actor.userId(), "DEVICE", devices.stream()
                .map(GatewayRefreshSessionRepository.DeviceView::deviceId).filter(java.util.Objects::nonNull).toList());
        return devices.stream().map(device -> new UserDeviceStatus(device.sessionId(), device.deviceId(),
                device.userAgent(), device.ipAddress(), device.lastSeen(), device.active(),
                device.deviceId() == null ? device.sessionId() == actor.sessionId() : device.deviceId().equals(currentDevice),
                device.deviceId() != null && blocked.contains(device.deviceId()))).toList();
    }

    @Transactional(readOnly = true)
    public List<UserIpStatus> ips(long userId, int limit, int offset) {
        var ips = logins.knownIps(userId, limit, offset);
        var blocked = blocks.activeValues(userId, "IP", ips.stream().map(GatewayLoginLogRepository.KnownIp::ipAddress).toList());
        return ips.stream().map(ip -> new UserIpStatus(ip.ipAddress(), ip.loginCount(), ip.lastSeen(),
                blocked.contains(ip.ipAddress()))).toList();
    }

    public AdminSessionRevokeResponse revokeDevice(long userId, String deviceId) {
        Instant now = Instant.now();
        return new AdminSessionRevokeResponse(sessions.revokeActiveForDevice(userId, deviceId, now), now);
    }

    @Transactional(noRollbackFor = SecurityVerificationRequiredException.class)
    public void blockDevice(JwtPrincipal actor, String deviceId, String emailCode, String totpCode) {
        if (deviceId.equals(sessions.deviceIdForSession(actor.userId(), actor.sessionId()).orElse(null)))
            throw new IllegalArgumentException("cannot block the current device");
        Instant now = Instant.now();
        requireVerification(actor.userId(), "SECURITY_SETTINGS", emailCode, totpCode, now);
        blocks.block(actor.userId(), "DEVICE", deviceId, now);
        sessions.revokeActiveForDevice(actor.userId(), deviceId, now);
    }

    @Transactional(noRollbackFor = SecurityVerificationRequiredException.class)
    public void unblockDevice(long userId, String deviceId, String emailCode, String totpCode) {
        Instant now = Instant.now();
        requireVerification(userId, "SECURITY_SETTINGS", emailCode, totpCode, now);
        blocks.unblock(userId, "DEVICE", deviceId, now);
    }

    @Transactional(noRollbackFor = SecurityVerificationRequiredException.class)
    public void blockIp(long userId, String ipAddress, String currentIp, String emailCode, String totpCode) {
        if (InetAddress.ofLiteral(ipAddress).equals(InetAddress.ofLiteral(currentIp)))
            throw new IllegalArgumentException("cannot block the current IP");
        Instant now = Instant.now();
        requireVerification(userId, "SECURITY_SETTINGS", emailCode, totpCode, now);
        blocks.block(userId, "IP", ipAddress, now);
        sessions.revokeActiveForIp(userId, ipAddress, now);
    }

    @Transactional(noRollbackFor = SecurityVerificationRequiredException.class)
    public void unblockIp(long userId, String ipAddress, String emailCode, String totpCode) {
        Instant now = Instant.now();
        requireVerification(userId, "SECURITY_SETTINGS", emailCode, totpCode, now);
        blocks.unblock(userId, "IP", ipAddress, now);
    }

    @Transactional(noRollbackFor = SecurityVerificationRequiredException.class)
    public void changePassword(long userId, String currentPassword, String newPassword, String emailCode, String totpCode) {
        security.requireCurrentPassword(userId, currentPassword);
        requireVerification(userId, "CHANGE_PASSWORD", emailCode, totpCode, Instant.now());
        security.updatePassword(userId, newPassword);
    }

    @Transactional(noRollbackFor = SecurityVerificationRequiredException.class)
    public UserSecurityService.Scene updateScene(long userId, String sceneCode, boolean enabled, String emailCode, String totpCode) {
        requireVerification(userId, "SECURITY_SETTINGS", emailCode, totpCode, Instant.now());
        return security.updateScene(userId, sceneCode, enabled, totpCode);
    }

    private void requireVerification(long userId, String scene, String emailCode, String totpCode, Instant now) {
        if (!verification.verify(userId, scene, emailCode, totpCode, now))
            throw new SecurityVerificationRequiredException();
    }
}
