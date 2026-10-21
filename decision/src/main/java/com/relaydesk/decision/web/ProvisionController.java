package com.relaydesk.decision.web;

import com.relaydesk.decision.domain.PrincipalUser;
import com.relaydesk.decision.dto.ProvisionRequest;
import com.relaydesk.decision.dto.ProvisionUpdate;
import com.relaydesk.decision.dto.ProvisionedUser;
import com.relaydesk.decision.service.ProvisionService;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Provisioning endpoints: privileged mutations authenticated by the channel service key
 * (see {@link InternalKeyFilter}). Channel performs its own admin authorization before
 * calling these; decision is the authoritative store for the data written here.
 */
@RestController
@RequestMapping("/internal/users")
public class ProvisionController {

    private final ProvisionService provisionService;

    public ProvisionController(ProvisionService provisionService) {
        this.provisionService = provisionService;
    }

    @PostMapping
    public ProvisionedUser provision(@RequestBody ProvisionRequest request) {
        return toResponse(provisionService.provision(request));
    }

    @PutMapping("/{userId}")
    public ProvisionedUser update(@PathVariable UUID userId, @RequestBody ProvisionUpdate update) {
        return toResponse(provisionService.update(userId, update));
    }

    private ProvisionedUser toResponse(PrincipalUser user) {
        return new ProvisionedUser(user.getId(), user.getEmail(), user.getDisplayName(), user.getRole(), user.isEnabled());
    }
}
