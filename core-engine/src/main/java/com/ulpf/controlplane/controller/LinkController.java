package com.ulpf.controlplane.controller;

import com.ulpf.controlplane.service.AdminLinkService;
import com.ulpf.controlplane.service.AdminLinkService.LinkInfo;
import com.ulpf.controlplane.service.AdminLinkService.LinkUse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public endpoints behind {@code /link/<token>} in the browser: {@code GET} says what the link is for,
 * {@code POST} uses it (invite: create a regular admin; reset: set a new password). Any unusable link answers 410.
 * The 256-bit token is the credential, so no login is needed.
 */
@RestController
@RequestMapping("/v1/links")
public class LinkController {

    private final AdminLinkService linkService;

    public LinkController(AdminLinkService linkService) {
        this.linkService = linkService;
    }

    @GetMapping("/{token}")
    public LinkInfo describe(@PathVariable String token) {
        return linkService.describe(token);
    }

    @PostMapping("/{token}")
    public ResponseEntity<Void> use(@PathVariable String token, @RequestBody LinkUse use) {
        linkService.use(token, use);
        return ResponseEntity.noContent().build();
    }
}
