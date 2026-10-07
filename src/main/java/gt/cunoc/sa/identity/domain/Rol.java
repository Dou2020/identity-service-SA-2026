package gt.cunoc.sa.identity.domain;

public enum Rol {
    SYS_ADMIN,
    BRANCH_ADMIN,
    CASHIER,
    DRIVER,
    CUSTOMER;

    /** Roles que pertenecen a una sucursal (usuario.sucursal_id obligatorio). */
    public boolean requiereSucursal() {
        return this == BRANCH_ADMIN || this == CASHIER || this == DRIVER;
    }
}
