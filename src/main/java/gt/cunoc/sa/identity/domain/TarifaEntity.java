package gt.cunoc.sa.identity.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Una versión de una tarifa. Nunca se actualiza: cambiar el valor inserta otra fila
 * con su vigente_desde, y lo ya calculado con la anterior no se toca.
 */
@Entity
@Table(name = "tarifa")
public class TarifaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20, updatable = false)
    private TipoTarifa tipo;

    @Column(nullable = false, precision = 12, scale = 4, updatable = false)
    private BigDecimal valor;

    @Column(name = "vigente_desde", nullable = false, updatable = false)
    private LocalDate vigenteDesde;

    @Column(name = "registrado_por", nullable = false, updatable = false)
    private UUID registradoPor;

    @Column(name = "registrado_en", nullable = false, updatable = false)
    private Instant registradoEn;

    protected TarifaEntity() {
    }

    public TarifaEntity(TipoTarifa tipo, BigDecimal valor, LocalDate vigenteDesde, UUID registradoPor, Instant ahora) {
        this.tipo = tipo;
        this.valor = valor;
        this.vigenteDesde = vigenteDesde;
        this.registradoPor = registradoPor;
        this.registradoEn = ahora;
    }

    public Long getId() {
        return id;
    }

    public TipoTarifa getTipo() {
        return tipo;
    }

    public BigDecimal getValor() {
        return valor;
    }

    public LocalDate getVigenteDesde() {
        return vigenteDesde;
    }

    public UUID getRegistradoPor() {
        return registradoPor;
    }

    public Instant getRegistradoEn() {
        return registradoEn;
    }
}
