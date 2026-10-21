package com.relaydesk.channel.web;

import com.relaydesk.channel.service.CategoryRef;
import com.relaydesk.channel.service.ReferenceDataService;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Desk-side reference data endpoint. The ETag (MD5 over the canonical JSON — a purely
 * non-cryptographic use, see {@link ReferenceDataService#categoriesEtag}) enables 304
 * responses; the body is revalidated with {@code no-cache}.
 */
@RestController
@PreAuthorize("hasAnyRole('ROLE_SUPPORT_AGENT','ROLE_TEAM_LEAD','ROLE_SUPPORT_ADMIN')")
public class ReferenceDataController {

    private final ReferenceDataService referenceDataService;

    public ReferenceDataController(ReferenceDataService referenceDataService) {
        this.referenceDataService = referenceDataService;
    }

    @GetMapping("/desk/ref/categories")
    public ResponseEntity<List<CategoryRef>> categories(
            @RequestHeader(name = "If-None-Match", required = false) String ifNoneMatch) {
        List<CategoryRef> data = referenceDataService.activeCategories();
        String etag = referenceDataService.categoriesEtag(data);
        String quoted = "\"" + etag + "\"";
        if (quoted.equals(ifNoneMatch)) {
            return ResponseEntity.status(HttpServletResponse.SC_NOT_MODIFIED).build();
        }
        return ResponseEntity.ok()
                .eTag(etag)
                .cacheControl(CacheControl.noCache())
                .body(data);
    }
}
