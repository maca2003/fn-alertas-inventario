package cl.duoc.fnalertas;

/**
 * Mensaje publicado por ms-inventario en la cola "inventario-alertas".
 */
public record AlertaStock(
        Long productoId,
        Integer stockActual,
        Integer stockMinimo,
        String fecha
) {

    public boolean esValida() {
        return productoId != null && stockActual != null && stockMinimo != null
                && stockActual >= 0 && stockMinimo >= 0;
    }
}
