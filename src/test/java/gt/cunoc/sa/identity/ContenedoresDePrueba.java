package gt.cunoc.sa.identity;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;

/**
 * PostgreSQL, Redis y RabbitMQ reales (las mismas imágenes que el compose): Flyway, el
 * ddl-auto=validate, los candados, el Lua de los enlaces y la DLQ se prueban contra los motores
 * de producción, no contra sustitutos en memoria.
 *
 * Las pruebas que usan @PruebaDeIntegracion comparten el contexto de Spring y, con él, un solo
 * juego de contenedores. RedisCaidoIT apaga el de Redis con prueba.redis-caido=true.
 */
@TestConfiguration(proxyBeanMethods = false)
@Import(Escenario.class)
public class ContenedoresDePrueba {

    @Bean
    @ServiceConnection
    PostgreSQLContainer<?> postgres() {
        return new PostgreSQLContainer<>("postgres:16-alpine");
    }

    @Bean
    @ServiceConnection(name = "redis")
    @ConditionalOnProperty(name = "prueba.redis-caido", havingValue = "false", matchIfMissing = true)
    GenericContainer<?> redis() {
        return new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);
    }

    @Bean
    @ServiceConnection
    RabbitMQContainer rabbit() {
        return new RabbitMQContainer("rabbitmq:4-management-alpine");
    }
}
