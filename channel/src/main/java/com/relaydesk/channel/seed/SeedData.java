package com.relaydesk.channel.seed;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * Deterministic demo content for the channel seeder: ticket subjects/bodies per category,
 * reply/note/escalation text, KB articles, contact names and attachment contents.
 */
final class SeedData {

    private SeedData() {
    }

    static final List<String> ORG_NAMES = List.of(
            "Acme Corp", "Globex Inc.", "Initech LLC", "Umbrella Health", "Northwind Traders",
            "Stark Industries", "Wayne Enterprises", "Hooli", "Pied Piper", "Vandelay Industries");

    static final List<String> ORG_PLANS = List.of(
            "Enterprise", "Growth", "Starter", "Enterprise", "Growth",
            "Enterprise", "Enterprise", "Growth", "Starter", "Growth");

    static final List<String> ORG_DOMAINS = List.of(
            "acme.test", "globex.test", "initech.test", "umbrella.test", "northwind.test",
            "stark.test", "wayne.test", "hooli.test", "piedpiper.test", "vandelay.test");

    /** 30 contact names — index 0 is Jordan Lee (Acme), the documented demo customer. */
    static final List<String> FIRST_NAMES = List.of(
            "Jordan", "Priya", "Marcus", "Elliot", "Rosa", "Tomás",
            "Samantha", "Deepak", "Claire", "Yusuf", "Helena", "Viktor",
            "Tony", "Pepper", "James", "Bruce", "Selina", "Lucius",
            "Gavin", "Nelson", "Dinesh", "Bertram", "Molly", "Clayton",
            "Art", "Cosmo", "George", "Penny", "Kramer", "Estelle");

    static final List<String> LAST_NAMES = List.of(
            "Lee", "Sharma", "Brooks", "Alderson", "Diaz", "Souza",
            "Carter", "Mehta", "Bennett", "Hassan", "Novak", "Petrov",
            "Stark", "Potts", "Barnes", "Wayne", "Kyle", "Fox",
            "Belson", "Bighetti", "Chugtai", "Gilfoyle", "Bartlett", "Packer",
            "Vandelay", "Kramer", "Costanza", "Hofstadter", "Richards", "Moneypenny");

    static final List<String> TITLES = List.of(
            "Support Manager", "Operations Lead", "Finance Analyst", "IT Administrator",
            "Procurement Officer", "Billing Specialist", "Security Officer", "Platform Engineer",
            "Account Owner", "Office Manager");

    // Ticket subjects per category (index = category index 0..4).
    static final List<List<String>> SUBJECTS = List.of(
            List.of("Invoice shows wrong amount for March", "Refund not received after cancellation",
                    "Payment failed but card was charged", "Plan upgrade double-billed",
                    "Invoice PDF missing tax breakdown", "Billing contact needs updating"),
            List.of("API returning 429 on batch import", "Webhook deliveries stopped overnight",
                    "Integration token keeps expiring", "Rate limit unclear for bulk export",
                    "Sandbox API keys revoked unexpectedly", "OAuth redirect URI rejected"),
            List.of("Suspicious login from unknown location", "MFA code not arriving via SMS",
                    "SSO group mapping broken after update", "User account locked after password reset",
                    "Audit log export missing admin actions", "Security review requested for new region"),
            List.of("New team member cannot access portal", "Training material for invoice exports",
                    "Walkthrough for first-time admins", "Onboarding checklist for large accounts",
                    "Best practices for workspace setup", "Help with workspace roles and permissions"),
            List.of("Feature request: dark mode for portal", "Suggest bulk actions for tickets",
                    "Feedback on new queue filters", "Wishlist: weekly SLA email digest",
                    "Ideas for a mobile ticket app", "Survey: how are we doing?"));

    static final List<List<String>> BODIES = List.of(
            List.of("Our March invoice shows $1,250 but the agreement says $980. Can you review the line items?",
                    "We cancelled our subscription two weeks ago and the refund has not appeared. Please check.",
                    "The payment portal returned an error, but our card statement shows the charge. Help!",
                    "We upgraded to Growth mid-month and were billed for both plans. Please prorate.",
                    "The downloadable invoice PDF omits the tax breakdown required by our finance team.",
                    "Our billing contact changed last month; please update the email on file."),
            List.of("Bulk imports intermittently return 429 even though we stay under the published limit.",
                    "Webhook deliveries stopped overnight and no failure events were received.",
                    "The integration token expires every hour despite a 30-day setting.",
                    "The documentation does not state the rate limit for the bulk export endpoint.",
                    "Our sandbox API keys were revoked without notice; production is unaffected.",
                    "The OAuth redirect URI we registered is being rejected with a generic error."),
            List.of("A login from an unknown location appeared in our audit log. Please investigate.",
                    "SMS MFA codes stopped arriving this morning for all our users.",
                    "After an SSO update, users are mapped to the wrong groups.",
                    "A user's account locked after a routine password reset; support cannot unlock it.",
                    "The audit log export omits actions performed by admins.",
                    "We need a security review before enabling a new region."),
            List.of("A new team member cannot access the customer portal after accepting the invite.",
                    "We need the training material for invoice exports before our finance rollout.",
                    "A walkthrough for first-time admins would help our new IT hire.",
                    "We are onboarding a 200-seat account and need the recommended checklist.",
                    "What is the best workspace setup for a mixed support and billing team?",
                    "Our workspace roles and permissions are confusing; can you explain?"),
            List.of("Dark mode would be great for late-night support shifts.",
                    "Bulk actions (close, merge) on tickets would save hours a week.",
                    "The new queue filters are great — one suggestion below.",
                    "A weekly SLA email digest would help managers.",
                    "A mobile ticket app would help our field teams.",
                    "Overall we are happy; a few notes in the details."));

    static final List<String> REPLY_BODIES = List.of(
            "Thanks for the details — I've reproduced the issue and I'm investigating now.",
            "I can confirm this is expected behaviour, but I've flagged the confusing part for the docs team.",
            "We've applied a fix on our side; please verify on your end when convenient.",
            "I've escalated the data question to our platform team and will update you shortly.");

    static final List<String> CUSTOMER_FOLLOWUPS = List.of(
            "Thanks — that worked. The remaining question can wait.",
            "Any update? We have a deadline at the end of the week.",
            "Thanks for the quick turnaround. Closing this from our side.",
            "Can you send the exact steps so we can reproduce it internally?");

    static final List<String> INTERNAL_NOTES = List.of(
            "Customer has been hitting this twice this quarter — check account flags.",
            "Likely related to the deployment on Tuesday; keep an eye on the rollout.",
            "Do not promise a date until the platform team confirms the fix window.",
            "Account is flagged for renewal next month; handle with care.");

    static final List<String> ESCALATION_REASONS = List.of(
            "Recurring billing defect affecting the whole org; needs platform review.",
            "Customer impact is high and the issue blocks their month-end close.",
            "Data inconsistency between core and channel records needs a decision from the lead.");

    static final List<String> KB_TITLES = List.of(
            "How refunds are processed and when to expect them",
            "Understanding your invoice line items",
            "Updating your billing contact and payment method",
            "Handling API rate limits (429) gracefully",
            "Troubleshooting stopped webhook deliveries",
            "Refreshing and rotating integration tokens",
            "Bulk export endpoints and their limits",
            "Recovering revoked sandbox API keys",
            "Fixing OAuth redirect URI rejections",
            "Setting up SSO group mappings",
            "Enabling MFA for your whole workspace",
            "Reviewing suspicious logins and alerts",
            "Exporting your audit log as CSV",
            "Onboarding checklist for large accounts",
            "First-day walkthrough for workspace admins",
            "Workspace roles and permissions explained",
            "Best practices for mixed support and billing teams",
            "Feature request triage — how suggestions become releases",
            "Using queue filters to find tickets fast",
            "Your weekly SLA digest, explained");

    static final List<String> KB_BODIES = List.of(
            "Refunds are issued to the original payment method within 5–10 business days of cancellation. "
                    + "If it has been longer, open a billing ticket with the invoice number.",
            "Every invoice lists plan charges, per-seat fees, prorations and tax separately. "
                    + "Line-item questions are answered from the billing portal.",
            "Admins can change the billing contact from Settings → Billing. The new contact receives "
                    + "all future invoice emails immediately.",
            "429 responses mean you crossed a rate limit. Read the Retry-After header and back off "
                    + "exponentially; bulk endpoints list their own limits in the docs.",
            "When webhooks stop, check the endpoint's delivery attempts page first, then verify your "
                    + "endpoint responds within 5 seconds and returns 2xx.",
            "Integration tokens can be rotated from Admin → API Keys. Old tokens remain valid for a "
                    + "30-minute grace window.",
            "Bulk export endpoints allow one concurrent export per org and return CSV with the same "
                    + "columns as the UI tables.",
            "Sandbox API keys are auto-revoked after 90 days of inactivity. Create a new key and "
                    + "update your CI secrets.",
            "OAuth redirect URIs must match exactly, including scheme and trailing slash. Register "
                    + "all allowed URIs before saving the app.",
            "SSO group mapping happens in the identity provider; changes propagate within 15 minutes. "
                    + "Use the test button before rolling out.",
            "Workspace owners can require MFA for all members. Users keep their existing app tokens "
                    + "when MFA is enabled.",
            "Suspicious login alerts include IP and location. Review them in Admin → Audit and revoke "
                    + "sessions for anything unfamiliar.",
            "The audit log exports as CSV with actor, action, resource and timestamps. Internal-only "
                    + "content never appears in exports.",
            "Work through the onboarding checklist: invite users, map teams, configure categories, "
                    + "then create your first test ticket.",
            "Admins land on the workspace overview. The walkthrough covers users, teams, queues and "
                    + "the first ticket flow.",
            "Roles: customers see their org's tickets; agents work team queues; leads decide "
                    + "escalations; admins configure everything.",
            "Keep billing and platform tickets in separate teams with a shared category for "
                    + "cross-team work.",
            "Feature requests are triaged monthly: duplicates merged, effort estimated, then "
                    + "scheduled by customer impact.",
            "Queue filters combine status, priority, team and search. Bookmarkable — share the URL "
                    + "to hand off a filtered view.",
            "The weekly SLA digest summarises breached, at-risk and on-track tickets per team, "
                    + "with average response times.");

    static final List<String> KB_TAGS = List.of(
            "billing,refunds", "billing,invoices", "billing,account",
            "api,rate-limits", "webhooks,troubleshooting", "api,tokens",
            "api,exports", "api,keys,sandbox", "api,oauth",
            "sso,security", "mfa,security", "security,audit",
            "audit,exports", "onboarding,checklist", "onboarding,admins",
            "roles,permissions", "teams,best-practices", "feedback,product",
            "queue,filters", "sla,reports");

    static final List<Map<String, String>> ATTACHMENT_FILES = List.of(
            Map.of("name", "invoice-march.txt", "type", "text/plain",
                    "body", "Invoice #INV-2026-0314\nCustomer: Acme Corp\nAmount: 1250.00 USD\n"),
            Map.of("name", "error-log.txt", "type", "text/plain",
                    "body", "2026-03-01T08:12:44Z ERROR api-gateway: upstream timeout after 5000ms\n"),
            Map.of("name", "api-response.txt", "type", "text/plain",
                    "body", "HTTP/1.1 429 Too Many Requests\nRetry-After: 60\n"),
            Map.of("name", "config-export.csv", "type", "text/csv",
                    "body", "key,value\nregion,eu-west\nplan,growth\n"),
            Map.of("name", "screenshot-notes.txt", "type", "text/plain",
                    "body", "Screenshot notes:\n1. Error banner on checkout\n2. Console shows 500 on POST /pay\n"));

    static SeedMultipartFile attachment(int i) {
        Map<String, String> f = ATTACHMENT_FILES.get(Math.floorMod(i, ATTACHMENT_FILES.size()));
        return new SeedMultipartFile("file", f.get("name"), f.get("type"),
                f.get("body").getBytes(StandardCharsets.UTF_8));
    }

    static String subject(int category, int i) {
        List<String> list = SUBJECTS.get(Math.floorMod(category, SUBJECTS.size()));
        return list.get(Math.floorMod(i, list.size()));
    }

    static String body(int category, int i) {
        List<String> list = BODIES.get(Math.floorMod(category, BODIES.size()));
        return list.get(Math.floorMod(i, list.size()));
    }
}
