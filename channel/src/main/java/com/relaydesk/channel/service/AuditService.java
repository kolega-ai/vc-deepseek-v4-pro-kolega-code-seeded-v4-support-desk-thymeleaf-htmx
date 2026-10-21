package com.relaydesk.channel.service;

import com.relaydesk.channel.domain.AuditEvent;
import com.relaydesk.channel.dto.AuditFilter;
import com.relaydesk.channel.dto.AuditRow;
import com.relaydesk.channel.repo.AuditEventRepository;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Append-only audit log. Every state transition and privileged action writes one event with
 * actor, action, before/after values, timestamp, and the authorization basis.
 *
 * <p><b>No code path ever updates or deletes rows of this table</b> — the repository only
 * inserts new instances, and no endpoint modifies or deletes them.
 */
@Service
@Transactional
public class AuditService {

    private final AuditEventRepository repo;

    public AuditService(AuditEventRepository repo) {
        this.repo = repo;
    }

    /** Insert-only record of an action. {@code p} may be null for system/unauthenticated actions. */
    public void record(RequestPrincipal p, String action, String resourceType, String resourceId,
                       String resourceRef, String before, String after) {
        AuditEvent e = new AuditEvent();
        e.setAction(action);
        e.setResourceType(resourceType);
        e.setResourceId(resourceId);
        e.setResourceRef(resourceRef);
        e.setBeforeValue(before);
        e.setAfterValue(after);
        if (p != null) {
            e.setActorId(p.userId());
            e.setActorEmail(p.email());
            e.setAuthzBasis(truncate(authzBasis(p), 500));
        }
        repo.save(e);
    }

    /** One read event per request (never per record). */
    public void recordRead(RequestPrincipal p, String resourceClass, int count) {
        record(p, "read." + resourceClass, "READ", null, null, null, "count=" + count);
    }

    public Page<AuditRow> query(AuditFilter f, Pageable pageable) {
        AuditFilter filter = f == null ? new AuditFilter(null, null, null, null, null, null) : f;
        Specification<AuditEvent> spec = (root, query, cb) -> {
            List<Predicate> ps = new ArrayList<>();
            if (filter.actorEmail() != null) {
                ps.add(cb.like(cb.lower(root.get("actorEmail")),
                        "%" + filter.actorEmail().toLowerCase(Locale.ROOT) + "%"));
            }
            if (filter.action() != null) {
                ps.add(cb.equal(root.get("action"), filter.action()));
            }
            if (filter.resourceType() != null) {
                ps.add(cb.equal(root.get("resourceType"), filter.resourceType()));
            }
            if (filter.resourceRef() != null) {
                ps.add(cb.equal(root.get("resourceRef"), filter.resourceRef()));
            }
            if (filter.from() != null) {
                ps.add(cb.greaterThanOrEqualTo(root.get("occurredAt"),
                        filter.from().atStartOfDay(ZoneOffset.UTC).toInstant()));
            }
            if (filter.to() != null) {
                ps.add(cb.lessThan(root.get("occurredAt"),
                        filter.to().plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant()));
            }
            return cb.and(ps.toArray(new Predicate[0]));
        };
        PageRequest pr = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(),
                Sort.by(Sort.Direction.DESC, "occurredAt"));
        return repo.findAll(spec, pr).map(this::toRow);
    }

    public List<AuditRow> forTicket(UUID ticketId) {
        return repo.findByResourceIdOrderByOccurredAtAsc(ticketId.toString()).stream()
                .map(this::toRow)
                .toList();
    }

    private AuditRow toRow(AuditEvent e) {
        return new AuditRow(e.getId(), e.getOccurredAt(), e.getActorEmail(), e.getAction(),
                e.getResourceType(), e.getResourceRef(), e.getBeforeValue(), e.getAfterValue(),
                e.getAuthzBasis());
    }

    private String authzBasis(RequestPrincipal p) {
        return "role=" + p.role()
                + ";teams=" + String.join(",", p.teamIds().stream().map(UUID::toString).sorted().toList())
                + ";orgs=" + String.join(",", p.orgIds().stream().map(UUID::toString).sorted().toList());
    }

    private String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
