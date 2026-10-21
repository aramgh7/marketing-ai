package com.marketingai.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;
import javax.naming.directory.DirContext;
import javax.naming.directory.InitialDirContext;
import java.util.ArrayList;
import java.util.Hashtable;
import java.util.List;

/**
 * Roadmap Phase 10: minimal DNS resolver for domain verification (TXT + CNAME). Failures return an empty
 * list and are logged; the caller reports "record not found".
 */
public interface DnsLookup {

    List<String> txt(String name);

    List<String> cname(String name);

    @Slf4j
    @Component
    class Jndi implements DnsLookup {

        private static final String TIMEOUT_MS = "3000";

        @Override
        public List<String> txt(String name) {
            return query(name, "TXT");
        }

        @Override
        public List<String> cname(String name) {
            return query(name, "CNAME");
        }

        private List<String> query(String name, String type) {
            List<String> out = new ArrayList<>();
            try {
                Hashtable<String, String> env = new Hashtable<>();
                env.put("java.naming.factory.initial", "com.sun.jndi.dns.DnsContextFactory");
                env.put("com.sun.jndi.dns.timeout.initial", TIMEOUT_MS);
                env.put("com.sun.jndi.dns.timeout.retries", "1");
                DirContext ctx = new InitialDirContext(env);
                try {
                    Attributes attrs = ctx.getAttributes(name, new String[]{type});
                    Attribute a = attrs.get(type);
                    if (a != null) {
                        for (int i = 0; i < a.size(); i++) out.add(String.valueOf(a.get(i)));
                    }
                } finally {
                    ctx.close();
                }
            } catch (Exception e) {
                log.debug("DNS {} lookup failed for {}: {}", type, name, e.getMessage());
            }
            return out;
        }
    }
}
