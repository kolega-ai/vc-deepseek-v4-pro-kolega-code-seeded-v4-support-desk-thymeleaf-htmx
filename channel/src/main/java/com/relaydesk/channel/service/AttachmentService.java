package com.relaydesk.channel.service;

import com.relaydesk.channel.config.AppProperties;
import com.relaydesk.channel.domain.Attachment;
import com.relaydesk.channel.domain.AttachmentCategory;
import com.relaydesk.channel.domain.Ticket;
import com.relaydesk.channel.domain.UserAccount;
import com.relaydesk.channel.dto.AttachmentRow;
import com.relaydesk.channel.error.ResourceNotFoundException;
import com.relaydesk.channel.repo.AttachmentRepository;
import com.relaydesk.channel.repo.TicketRepository;
import com.relaydesk.channel.repo.UserAccountRepository;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Ticket attachments: two categories — CUSTOMER_ATTACHMENT (customer or staff upload; visible
 * to the ticket org's customers + scoped staff) and INTERNAL_DOCUMENT (staff only, never
 * visible to customers). Downloads are authorized per request and audited per download.
 */
@Service
@Transactional
public class AttachmentService {

    public record StoredAttachment(Attachment meta, Path path) {
    }

    private final AttachmentRepository attachments;
    private final TicketRepository tickets;
    private final UserAccountRepository users;
    private final FileStorageService storage;
    private final AuditService audit;
    private final AppProperties properties;

    public AttachmentService(AttachmentRepository attachments, TicketRepository tickets,
                             UserAccountRepository users, FileStorageService storage,
                             AuditService audit, AppProperties properties) {
        this.attachments = attachments;
        this.tickets = tickets;
        this.users = users;
        this.storage = storage;
        this.audit = audit;
        this.properties = properties;
    }

    public Attachment upload(RequestPrincipal p, UUID ticketId, MultipartFile file,
                             AttachmentCategory category) {
        Ticket t = tickets.findById(ticketId)
                .orElseThrow(() -> new ResourceNotFoundException("ticket not found"));
        if (p.isStaff()) {
            AccessGuard.requireTicketWorkable(p, t);
        } else {
            AccessGuard.requireTicketVisible(p, t);
            if (category == AttachmentCategory.INTERNAL_DOCUMENT) {
                throw new AccessDeniedException("customers cannot upload internal documents");
            }
        }
        if (category == null) {
            throw new IllegalArgumentException("attachment category required");
        }
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("file is empty");
        }
        if (!p.isStaff()) {
            int countLimit = p.limit("max_attachments_per_ticket", 3);
            if (attachments.countByTicketId(ticketId) >= countLimit) {
                throw new IllegalStateException("attachment limit reached for this ticket");
            }
            int mbLimit = p.limit("max_attachment_mb", 10);
            if (file.getSize() > (long) mbLimit * 1024 * 1024) {
                throw new IllegalStateException("attachment exceeds the size limit");
            }
        }
        if (file.getSize() > properties.maxUploadBytes()) {
            throw new IllegalStateException("attachment too large");
        }
        FileStorageService.StoredFile stored = category == AttachmentCategory.INTERNAL_DOCUMENT
                ? storage.storeRaw(file)
                : storage.store(file);
        Attachment a = new Attachment();
        a.setTicketId(ticketId);
        a.setUploaderId(p.userId());
        a.setCategory(category);
        a.setOriginalName(stored.originalName());
        a.setStoredName(stored.storedName());
        a.setContentType(stored.contentType());
        a.setSizeBytes(stored.sizeBytes());
        attachments.save(a);
        audit.record(p, "attachment.uploaded", "attachment", a.getId().toString(),
                a.getOriginalName(), null, a.getStoredName());
        return a;
    }

    /** Authorized, per-download-audited download. Internal documents require staff workability. */
    public StoredAttachment open(RequestPrincipal p, UUID attachmentId) {
        Attachment a = attachments.findById(attachmentId)
                .orElseThrow(() -> new ResourceNotFoundException("attachment not found"));
        Ticket t = tickets.findById(a.getTicketId())
                .orElseThrow(() -> new ResourceNotFoundException("ticket not found"));
        AccessGuard.requireTicketVisible(p, t);
        if (a.getCategory() == AttachmentCategory.INTERNAL_DOCUMENT) {
            AccessGuard.requireTicketWorkable(p, t);
        }
        audit.record(p, "attachment.downloaded", "attachment", a.getId().toString(),
                a.getOriginalName(), null, "downloaded");
        return new StoredAttachment(a, storage.resolve(a.getStoredName()));
    }

    public List<AttachmentRow> listForTicket(RequestPrincipal p, UUID ticketId) {
        Ticket t = tickets.findById(ticketId)
                .orElseThrow(() -> new ResourceNotFoundException("ticket not found"));
        AccessGuard.requireTicketVisible(p, t);
        boolean customer = !p.isStaff();
        List<Attachment> list = attachments.findByTicketIdOrderByCreatedAtAsc(ticketId);
        if (customer) {
            list = list.stream().filter(a -> a.getCategory() == AttachmentCategory.CUSTOMER_ATTACHMENT).toList();
        }
        Set<UUID> uploaderIds = list.stream().map(Attachment::getUploaderId).collect(Collectors.toSet());
        Map<UUID, UserAccount> uploaders = userMap(uploaderIds);
        return list.stream()
                .map(a -> {
                    UserAccount u = uploaders.get(a.getUploaderId());
                    return new AttachmentRow(a.getId(), a.getOriginalName(), a.getContentType(),
                            a.getSizeBytes(), a.getCategory(),
                            u == null ? null : u.getDisplayName(), a.getCreatedAt());
                })
                .toList();
    }

    private Map<UUID, UserAccount> userMap(Collection<UUID> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        return users.findAllById(ids).stream()
                .collect(Collectors.toMap(UserAccount::getId, u -> u));
    }
}
