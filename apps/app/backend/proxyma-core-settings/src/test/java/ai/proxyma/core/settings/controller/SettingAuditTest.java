package ai.proxyma.core.settings.controller;

import ai.proxyma.core.common.audit.Audit;
import ai.proxyma.core.common.audit.AuditCategory;
import ai.proxyma.core.common.audit.AuditEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A setting changed is recorded under Configuration - and a credential's VALUE never is, only that
 * it changed. Asked of the field's name as well as of the annotation the write path encrypts on,
 * since a log is the last place a secret should turn up and a new credential field might be added
 * without its annotation.
 */
class SettingAuditTest {

    @BeforeEach
    void empty() {
        Audit.drain(Integer.MAX_VALUE);
    }

    private static AuditEntry saved(String prefix, String field, String value) {
        SettingsController.auditSaved("u-1", prefix, field, value, false);
        return Audit.drain(1).get(0);
    }

    @Test
    @DisplayName("an ordinary setting is recorded with its new value")
    void plainValueIsKept() {
        AuditEntry e = saved("retention", "audit-days", "30");
        assertEquals(AuditCategory.CONFIG, e.category());
        assertEquals("setting.saved", e.action());
        assertEquals("retention.audit-days", e.target());
        assertEquals("30", e.detail().get("value"));
    }

    @Test
    @DisplayName("anything named like a credential is recorded without its value")
    void secretValueIsNeverKept() {
        for (String field : new String[] {"api-key", "token", "client-secret", "password", "credential"}) {
            AuditEntry e = saved("whatever", field, "sk-live-1234567890");
            assertNull(e.detail().get("value"), field);
            assertFalse(String.valueOf(e.summary()).contains("sk-live"), field);
            assertTrue(e.summary().contains("value not recorded"), field);
        }
    }

    @Test
    @DisplayName("a switch whose name merely mentions secrets or tokens keeps its value")
    void switchesAreNotSecrets() {
        assertEquals("true", saved("guardrail", "detect-secrets", "true").detail().get("value"));
        assertEquals("4096", saved("chat", "max-output-tokens", "4096").detail().get("value"));
        assertNull(saved("jira", "api-token", "abc").detail().get("value"));
        assertNull(saved("m365", "client_secret", "abc").detail().get("value"));
    }

    @Test
    @DisplayName("a cleared setting carries no value at all")
    void clearedHasNoValue() {
        SettingsController.auditSaved("u-1", "proxy", "host", null, true);
        AuditEntry e = Audit.drain(1).get(0);
        assertEquals("setting.cleared", e.action());
        assertNull(e.detail().get("value"));
    }

    @Test
    @DisplayName("the page's own state - the product tour seen - is not recorded")
    void uiStateIsNotRecorded() {
        SettingsController.auditSaved("u-1", "onboarding", "tour-completed", "true", false);
        assertTrue(Audit.drain(10).isEmpty());
    }
}
