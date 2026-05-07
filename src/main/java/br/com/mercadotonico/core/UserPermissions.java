package br.com.mercadotonico.core;

public final class UserPermissions {
    private UserPermissions() {
    }

    public static boolean canAccessPdv(String role) {
        return "ADMIN".equals(role) || "GERENTE".equals(role) || "CAIXA".equals(role);
    }

    public static boolean canAccessInventory(String role) {
        return "ADMIN".equals(role) || "GERENTE".equals(role) || "ESTOQUE".equals(role);
    }

    public static boolean canAccessReports(String role) {
        return "ADMIN".equals(role) || "GERENTE".equals(role);
    }

    public static boolean canAccessFinance(String role) {
        return "ADMIN".equals(role) || "GERENTE".equals(role);
    }

    public static boolean canManageSuppliers(String role) {
        return canAccessInventory(role);
    }

    public static boolean canImportXml(String role) {
        return canAccessInventory(role);
    }

    public static boolean canAccessFiado(String role) {
        return "ADMIN".equals(role) || "GERENTE".equals(role) || "CAIXA".equals(role);
    }
}
