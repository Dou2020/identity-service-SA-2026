/**
 * Casos de uso que no son de un solo recurso: el alta de cuentas que comparten auth, usuario,
 * sucursal y E5; E5 mismo, la purga y el admin inicial, que no tienen endpoint; y la regla de
 * alcance (un BRANCH_ADMIN solo ve su sucursal) que aplican los *Service de api/ con el recurso
 * ya cargado. Lo propio de un recurso (transacción y @PreAuthorize) vive en su *Service.
 */
package gt.cunoc.sa.identity.application;
