package com.relaydesk.channel.service;

import com.relaydesk.channel.domain.Notification;
import com.relaydesk.channel.dto.NotificationRow;
import com.relaydesk.channel.error.ResourceNotFoundException;
import com.relaydesk.channel.repo.NotificationPagingRepository;
import com.relaydesk.channel.repo.NotificationRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** User notifications (in-app only — internal content never leaks into customer notifications). */
@Service
@Transactional
public class NotificationService {

    private final NotificationRepository repo;
    private final NotificationPagingRepository paging;

    public NotificationService(NotificationRepository repo, NotificationPagingRepository paging) {
        this.repo = repo;
        this.paging = paging;
    }

    public void notifyUser(UUID userId, String type, String title, String message, String link) {
        if (userId == null) {
            return;
        }
        Notification n = new Notification();
        n.setUserId(userId);
        n.setType(truncate(type, 40));
        n.setTitle(truncate(title, 160));
        n.setMessage(truncate(message, 400));
        n.setLink(link == null ? null : truncate(link, 300));
        repo.save(n);
    }

    public Page<NotificationRow> forUser(RequestPrincipal p, Pageable pageable) {
        PageRequest pr = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(),
                Sort.by(Sort.Direction.DESC, "createdAt"));
        return paging.findByUserIdOrderByCreatedAtDesc(p.userId(), pr)
                .map(n -> new NotificationRow(n.getId(), n.getType(), n.getTitle(), n.getMessage(),
                        n.getLink(), n.getReadAt() != null, n.getCreatedAt()));
    }

    public long unread(RequestPrincipal p) {
        return repo.countByUserIdAndReadAtIsNull(p.userId());
    }

    public void markRead(RequestPrincipal p, UUID id) {
        Notification n = repo.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("notification not found"));
        if (!n.getUserId().equals(p.userId())) {
            throw new AccessDeniedException("notification does not belong to you");
        }
        if (n.getReadAt() == null) {
            n.setReadAt(Instant.now());
            repo.save(n);
        }
    }

    public void markAllRead(RequestPrincipal p) {
        List<Notification> unread = repo.findByUserIdAndReadAtIsNull(p.userId());
        unread.forEach(n -> n.setReadAt(Instant.now()));
        repo.saveAll(unread);
    }

    private String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }
}
