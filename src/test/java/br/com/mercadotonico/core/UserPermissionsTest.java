package br.com.mercadotonico.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UserPermissionsTest {
    @Test
    void shouldRestrictAdminModulesByRole() {
        assertTrue(UserPermissions.canAccessPdv("CAIXA"));
        assertFalse(UserPermissions.canAccessInventory("CAIXA"));
        assertFalse(UserPermissions.canAccessReports("CAIXA"));
        assertFalse(UserPermissions.canAccessFinance("CAIXA"));

        assertTrue(UserPermissions.canAccessInventory("ESTOQUE"));
        assertFalse(UserPermissions.canAccessPdv("ESTOQUE"));
        assertFalse(UserPermissions.canAccessReports("ESTOQUE"));

        assertTrue(UserPermissions.canAccessPdv("GERENTE"));
        assertTrue(UserPermissions.canAccessInventory("GERENTE"));
        assertTrue(UserPermissions.canAccessReports("GERENTE"));
        assertTrue(UserPermissions.canAccessFinance("GERENTE"));
    }
}
