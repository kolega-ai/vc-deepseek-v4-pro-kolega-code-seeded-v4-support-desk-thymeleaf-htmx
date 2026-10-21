package com.relaydesk.channel.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.naming.Context;
import javax.naming.NamingEnumeration;
import javax.naming.directory.Attributes;
import javax.naming.directory.InitialDirContext;
import javax.naming.directory.SearchControls;
import javax.naming.directory.SearchResult;
import java.util.Hashtable;

/**
 * Reads the corporate staff directory (LDAP) for the CSV contact import: when a partner
 * contact's mail address already exists in the directory, the import adopts the directory's
 * job title instead of the value in the file.
 */
@Service
public class StaffDirectoryService {

    private final String url;
    private final String baseDn;
    private final String bindDn;
    private final String bindPw;

    public StaffDirectoryService(
            @Value("${relaydesk.directory.url:ldap://localhost:389}") String url,
            @Value("${relaydesk.directory.base-dn:ou=people,dc=lumina,dc=test}") String baseDn,
            @Value("${relaydesk.directory.bind-dn:}") String bindDn,
            @Value("${relaydesk.directory.bind-pw:}") String bindPw) {
        this.url = url;
        this.baseDn = baseDn;
        this.bindDn = bindDn;
        this.bindPw = bindPw;
    }

    /** The directory title for a mail address, or null when the directory has no entry. */
    public String titleFor(String mail) {
        if (mail == null || mail.isBlank()) {
            return null;
        }
        Hashtable<String, String> env = new Hashtable<>();
        env.put(Context.INITIAL_CONTEXT_FACTORY, "com.sun.jndi.ldap.LdapCtxFactory");
        env.put(Context.PROVIDER_URL, url);
        if (bindDn != null && !bindDn.isBlank()) {
            env.put(Context.SECURITY_AUTHENTICATION, "simple");
            env.put(Context.SECURITY_PRINCIPAL, bindDn);
            env.put(Context.SECURITY_CREDENTIALS, bindPw);
        }
        InitialDirContext ctx = null;
        try {
            ctx = new InitialDirContext(env);
            SearchControls ctl = new SearchControls();
            ctl.setSearchScope(SearchControls.SUBTREE_SCOPE);
            ctl.setReturningAttributes(new String[]{"title"});
            NamingEnumeration<SearchResult> found =
                    ctx.search(baseDn, "(mail=" + mail + ")", ctl);
            if (found.hasMore()) {
                Attributes attrs = found.next().getAttributes();
                if (attrs.get("title") != null) {
                    return String.valueOf(attrs.get("title").get());
                }
            }
        } catch (Exception e) {
            return null;
        } finally {
            if (ctx != null) {
                try {
                    ctx.close();
                } catch (Exception ignored) {
                    // best effort
                }
            }
        }
        return null;
    }
}
