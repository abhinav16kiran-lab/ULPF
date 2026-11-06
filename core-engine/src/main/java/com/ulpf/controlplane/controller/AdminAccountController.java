package com.ulpf.controlplane.controller;

import com.ulpf.common.UlpfPrincipal;
import com.ulpf.common.db.AdminLinkRepository.AdminLink;
import com.ulpf.common.db.AdminLinkRepository.Kind;
import com.ulpf.common.db.UserRepository;
import com.ulpf.controlplane.model.User;
import com.ulpf.controlplane.service.AdminAccountService;
import com.ulpf.controlplane.service.AdminAccountService.AdminSummary;
import com.ulpf.controlplane.service.AdminLinkService;
import com.ulpf.controlplane.service.AdminLinkService.CreatedLink;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The super-admin's Admins screen: list admins, create invite and reset links, list and cancel pending links,
 * revoke admins. {@code SpringSecurity} lets only {@code ROLE_SUPER_ADMIN} reach {@code /v1/admin/admins/**};
 * the services check the caller again, so a wiring mistake cannot open these actions to every admin.
 */
@RestController
@RequestMapping("/v1/admin/admins")
public class AdminAccountController {

    /** A pending link as the screen shows it: never the token hash. */
    public record PendingLink(String linkId, Kind kind, String label, String targetUsername, Instant createdAt, Instant expiresAt) {}

    record InviteRequest(String label) {}

    private final AdminAccountService accountService;
    private final AdminLinkService linkService;
    private final UserRepository userRepository;

    public AdminAccountController(AdminAccountService accountService, AdminLinkService linkService, UserRepository userRepository) {
        this.accountService = accountService;
        this.linkService = linkService;
        this.userRepository = userRepository;
    }

    @GetMapping
    public List<AdminSummary> list() {
        return accountService.listAdmins();
    }

    @PostMapping("/invites")
    public ResponseEntity<CreatedLink> invite(@AuthenticationPrincipal UlpfPrincipal principal,
                                              @RequestBody(required = false) InviteRequest request) {
        String label = request == null ? null : request.label();
        return ResponseEntity.status(HttpStatus.CREATED).body(linkService.createInvite(principal.id(), label));
    }

    @PostMapping("/{userId}/reset-link")
    public ResponseEntity<CreatedLink> resetLink(@AuthenticationPrincipal UlpfPrincipal principal, @PathVariable String userId) {
        return ResponseEntity.status(HttpStatus.CREATED).body(linkService.createReset(principal.id(), userId));
    }

    @DeleteMapping("/{userId}")
    public ResponseEntity<Void> revoke(@AuthenticationPrincipal UlpfPrincipal principal, @PathVariable String userId) {
        accountService.revoke(principal.id(), userId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/links")
    public List<PendingLink> pendingLinks() {
        List<AdminLink> pending = linkService.pending();
        Map<String, String> usernames = userRepository.findUsersByRole(com.ulpf.controlplane.model.Role.ADMIN).stream()
                .collect(Collectors.toMap(User::userId, User::username, (a, b) -> a));
        return pending.stream()
                .map(l -> new PendingLink(l.linkId(), l.kind(), l.label(),
                        l.targetUserId() == null ? null : usernames.get(l.targetUserId()), l.createdAt(), l.expiresAt()))
                .toList();
    }

    @DeleteMapping("/links/{linkId}")
    public ResponseEntity<Void> cancelLink(@PathVariable String linkId) {
        linkService.cancel(linkId);
        return ResponseEntity.noContent().build();
    }
}
