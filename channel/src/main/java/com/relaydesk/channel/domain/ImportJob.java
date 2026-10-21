package com.relaydesk.channel.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "import_jobs")
@Getter
@Setter
@NoArgsConstructor
public class ImportJob {

    @Id
    @Column(name = "id")
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 20)
    private ImportKind kind;

    @Column(name = "file_name", nullable = false, length = 255)
    private String fileName;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ImportStatus status;

    @Column(name = "rows_total", nullable = false)
    private int rowsTotal;

    @Column(name = "rows_ok", nullable = false)
    private int rowsOk;

    @Column(name = "rows_failed", nullable = false)
    private int rowsFailed;

    /** CSV report of per-row errors (line number, message). */
    @Column(name = "error_report", columnDefinition = "text")
    private String errorReport;

    @Column(name = "created_by_id", nullable = false)
    private UUID createdById;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @PrePersist
    void onCreate() {
        if (id == null) {
            id = UUID.randomUUID();
        }
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
