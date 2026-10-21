package com.relaydesk.channel.service;

import com.relaydesk.channel.client.CoreClient;
import com.relaydesk.channel.domain.ImportJob;
import com.relaydesk.channel.domain.ImportKind;
import com.relaydesk.channel.domain.ImportStatus;
import com.relaydesk.channel.dto.ImportJobRow;
import com.relaydesk.channel.dto.OrgSummaryRow;
import com.relaydesk.channel.error.CoreClientException;
import com.relaydesk.channel.error.ResourceNotFoundException;
import com.relaydesk.channel.repo.ImportJobRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Admin CSV imports (CUSTOMERS / CONTACTS). Parsing is a hand-written RFC 4180 parser
 * ({@link CsvSupport}); every row is validated and executed through {@link CoreClient} with
 * the admin principal; per-row errors are collected into a downloadable report.
 */
@Service
@Transactional
public class ImportService {

    private static final long MAX_FILE_BYTES = 2L * 1024 * 1024;
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final List<String> CUSTOMERS_HEADER = List.of(
            "organization_name", "plan", "address", "contact_first_name",
            "contact_last_name", "contact_email", "contact_phone", "contact_title");
    private static final List<String> CONTACTS_HEADER = List.of(
            "organization_name", "first_name", "last_name", "email", "phone", "title");

    private final ImportJobRepository jobs;
    private final CoreClient core;
    private final AuditService audit;
    private final StaffDirectoryService directory;

    public ImportService(ImportJobRepository jobs, CoreClient core, AuditService audit,
                         StaffDirectoryService directory) {
        this.jobs = jobs;
        this.core = core;
        this.audit = audit;
        this.directory = directory;
    }

    public byte[] template(ImportKind kind) {
        if (kind == ImportKind.CUSTOMERS) {
            return csv(CUSTOMERS_HEADER,
                    List.of("Acme Corp", "Enterprise", "1 Main St", "Jane", "Doe",
                            "jane.doe@acme.test", "+1-555-0100", "Support Manager"));
        }
        return csv(CONTACTS_HEADER,
                List.of("Acme Corp", "Jane", "Doe", "jane.doe@acme.test", "+1-555-0100", "Support Manager"));
    }

    public ImportJob run(RequestPrincipal admin, ImportKind kind, MultipartFile csvFile) {
        requireAdmin(admin);
        ImportJob job = new ImportJob();
        job.setKind(kind);
        job.setFileName(csvFile == null ? "unknown" : csvFile.getOriginalFilename());
        job.setCreatedById(admin.userId());

        if (csvFile == null || csvFile.isEmpty()) {
            return fail(admin, job, List.of("line 1: empty file"));
        }
        String contentType = csvFile.getContentType();
        if (contentType == null
                || !(contentType.equalsIgnoreCase("text/csv") || contentType.equalsIgnoreCase("text/plain"))) {
            throw new IllegalArgumentException("only CSV files are accepted");
        }
        if (csvFile.getSize() > MAX_FILE_BYTES) {
            throw new IllegalArgumentException("file exceeds 2 MB");
        }
        byte[] bytes;
        try {
            bytes = csvFile.getBytes();
        } catch (IOException e) {
            return fail(admin, job, List.of("line 1: cannot read file"));
        }
        List<String[]> rows;
        try {
            rows = CsvSupport.parse(new String(bytes, StandardCharsets.UTF_8));
        } catch (IllegalArgumentException e) {
            return fail(admin, job, List.of("line 1: " + e.getMessage()));
        }
        if (rows.isEmpty()) {
            return fail(admin, job, List.of("line 1: empty file"));
        }
        List<String> header = normalize(rows.get(0));
        List<String> expected = kind == ImportKind.CUSTOMERS ? CUSTOMERS_HEADER : CONTACTS_HEADER;
        if (!header.equals(expected)) {
            return fail(admin, job, List.of("line 1: unexpected header — expected " + String.join(",", expected)));
        }

        List<String> errors = new ArrayList<>();
        Map<String, OrgSummaryRow> orgsByName = new HashMap<>();
        try {
            for (OrgSummaryRow o : core.allOrgs(admin)) {
                orgsByName.put(o.name(), o);
            }
        } catch (CoreClientException e) {
            return fail(admin, job, List.of("line 1: " + e.getMessage()));
        }

        int rowsOk = 0;
        int rowsFailed = 0;
        for (int i = 1; i < rows.size(); i++) {
            String line = "line " + (i + 1);
            String[] r = rows.get(i);
            try {
                if (kind == ImportKind.CUSTOMERS) {
                    executeCustomersRow(admin, r, orgsByName);
                } else {
                    executeContactsRow(admin, r, orgsByName);
                }
                rowsOk++;
            } catch (IllegalArgumentException | CoreClientException e) {
                rowsFailed++;
                errors.add(line + ": " + e.getMessage());
            }
        }
        job.setRowsTotal(rows.size() - 1);
        job.setRowsOk(rowsOk);
        job.setRowsFailed(rowsFailed);
        job.setStatus(rowsFailed == 0 && job.getRowsTotal() > 0 ? ImportStatus.COMPLETED : ImportStatus.FAILED);
        job.setErrorReport(buildErrorReport(errors));
        job.setFinishedAt(Instant.now());
        jobs.save(job);
        audit.record(admin, "import.run", "import", job.getId().toString(), job.getFileName(),
                null, "kind=" + kind + " rows=" + job.getRowsTotal()
                        + " ok=" + rowsOk + " failed=" + rowsFailed);
        return job;
    }

    public Page<ImportJobRow> jobs(Pageable pageable) {
        PageRequest pr = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(),
                Sort.by(Sort.Direction.DESC, "createdAt"));
        return jobs.findAll(pr).map(j -> new ImportJobRow(j.getId(), j.getKind(), j.getFileName(),
                j.getStatus(), j.getRowsTotal(), j.getRowsOk(), j.getRowsFailed(),
                j.getCreatedAt(), j.getFinishedAt()));
    }

    public byte[] errorReport(UUID jobId) {
        ImportJob job = jobs.findById(jobId)
                .orElseThrow(() -> new ResourceNotFoundException("import job not found"));
        return job.getErrorReport() == null ? new byte[0]
                : job.getErrorReport().getBytes(StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------------ row execution

    private void executeCustomersRow(RequestPrincipal admin, String[] r,
                                     Map<String, OrgSummaryRow> orgsByName) {
        String orgName = required(r, 0, "organization_name");
        String plan = value(r, 1);
        String address = value(r, 2);
        String firstName = required(r, 3, "contact_first_name");
        String lastName = required(r, 4, "contact_last_name");
        String email = required(r, 5, "contact_email");
        if (!EMAIL.matcher(email).matches()) {
            throw new IllegalArgumentException("invalid email: " + email);
        }
        String phone = value(r, 6);
        String title = value(r, 7);
        OrgSummaryRow org = orgsByName.get(orgName);
        if (org == null) {
            org = core.createOrg(admin, orgName, plan, address);
            orgsByName.put(orgName, org);
        }
        core.createContact(admin, org.id(), firstName, lastName, email, phone, title);
    }

    private void executeContactsRow(RequestPrincipal admin, String[] r,
                                    Map<String, OrgSummaryRow> orgsByName) {
        String orgName = required(r, 0, "organization_name");
        String firstName = required(r, 1, "first_name");
        String lastName = required(r, 2, "last_name");
        String email = required(r, 3, "email");
        if (!EMAIL.matcher(email).matches()) {
            throw new IllegalArgumentException("invalid email: " + email);
        }
        String phone = value(r, 4);
        String title = value(r, 5);
        OrgSummaryRow org = orgsByName.get(orgName);
        if (org == null) {
            throw new IllegalArgumentException("unknown organization: " + orgName);
        }
        String directoryTitle = directory.titleFor(email);
        core.createContact(admin, org.id(), firstName, lastName, email, phone,
                directoryTitle == null ? title : directoryTitle);
    }

    // ------------------------------------------------------------------ helpers

    private ImportJob fail(RequestPrincipal admin, ImportJob job, List<String> errors) {
        job.setRowsTotal(0);
        job.setRowsOk(0);
        job.setRowsFailed(0);
        job.setStatus(ImportStatus.FAILED);
        job.setErrorReport(buildErrorReport(errors));
        job.setFinishedAt(Instant.now());
        jobs.save(job);
        audit.record(admin, "import.run", "import", job.getId().toString(), job.getFileName(),
                null, "kind=" + job.getKind() + " rows=0 ok=0 failed=0");
        return job;
    }

    private String buildErrorReport(List<String> errors) {
        StringBuilder sb = new StringBuilder();
        sb.append(CsvSupport.encode(List.of("line", "message"))).append("\r\n");
        for (String e : errors) {
            int idx = e.indexOf(": ");
            String line = idx < 0 ? "" : e.substring(0, idx);
            String message = idx < 0 ? e : e.substring(idx + 2);
            sb.append(CsvSupport.encode(List.of(line, message))).append("\r\n");
        }
        return sb.toString();
    }

    private List<String> normalize(String[] header) {
        return Arrays.stream(header).map(h -> h == null ? "" : h.trim().toLowerCase(Locale.ROOT)).toList();
    }

    private String required(String[] r, int i, String field) {
        String v = value(r, i);
        if (v == null || v.isBlank()) {
            throw new IllegalArgumentException("missing " + field);
        }
        return v;
    }

    private String value(String[] r, int i) {
        if (r == null || i >= r.length || r[i] == null || r[i].isBlank()) {
            return null;
        }
        return r[i].trim();
    }

    private byte[] csv(List<String> header, List<String> row) {
        return (CsvSupport.encode(header) + "\r\n" + CsvSupport.encode(row) + "\r\n")
                .getBytes(StandardCharsets.UTF_8);
    }

    private void requireAdmin(RequestPrincipal p) {
        if (p == null || !p.isAdmin()) {
            throw new AccessDeniedException("admin only");
        }
    }
}
