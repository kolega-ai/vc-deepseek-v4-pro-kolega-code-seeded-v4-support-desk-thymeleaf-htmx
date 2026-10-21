package com.relaydesk.channel.web;

import com.relaydesk.channel.domain.ImportJob;
import com.relaydesk.channel.domain.ImportKind;
import com.relaydesk.channel.error.ResourceNotFoundException;
import com.relaydesk.channel.service.ImportService;
import com.relaydesk.channel.service.RequestPrincipal;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.UUID;

/** Admin CSV imports: run, history, template and per-row error report downloads. */
@Controller
@PreAuthorize("hasRole('ROLE_SUPPORT_ADMIN')")
public class AdminImportController {

    private final ImportService importService;

    public AdminImportController(ImportService importService) {
        this.importService = importService;
    }

    @GetMapping("/admin/imports")
    public String imports(@RequestParam(name = "page", defaultValue = "0") int page, Model model) {
        model.addAttribute("jobs", importService.jobs(PageRequest.of(Math.max(0, page), 15)));
        return "admin/admin-imports";
    }

    @PostMapping("/admin/imports/run")
    public String run(@CurrentUser RequestPrincipal principal,
                      @RequestParam ImportKind kind,
                      @RequestParam("file") MultipartFile file,
                      RedirectAttributes redirectAttributes) {
        ImportJob job = importService.run(principal, kind, file);
        redirectAttributes.addFlashAttribute("flashSuccess",
                "Import finished: " + job.getRowsOk() + " ok, " + job.getRowsFailed() + " failed");
        return "redirect:/admin/imports";
    }

    @GetMapping("/admin/imports/template/{kind}")
    public org.springframework.http.ResponseEntity<byte[]> template(@PathVariable ImportKind kind) {
        byte[] csv = importService.template(kind);
        return Downloads.csv(kind == ImportKind.CUSTOMERS
                ? "customers-template.csv" : "contacts-template.csv",
                new String(csv, java.nio.charset.StandardCharsets.UTF_8));
    }

    @GetMapping("/admin/imports/{id}/errors")
    public Object errors(@PathVariable UUID id, RedirectAttributes redirectAttributes) {
        byte[] report;
        try {
            report = importService.errorReport(id);
        } catch (ResourceNotFoundException e) {
            redirectAttributes.addFlashAttribute("flashError", "Import job not found");
            return "redirect:/admin/imports";
        }
        if (report == null || report.length == 0) {
            redirectAttributes.addFlashAttribute("flashError", "No errors for this import");
            return "redirect:/admin/imports";
        }
        return Downloads.csv("import-errors-" + id + ".csv",
                new String(report, java.nio.charset.StandardCharsets.UTF_8));
    }
}
