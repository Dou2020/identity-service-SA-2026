package gt.cunoc.sa.identity.infrastructure.web;

import java.util.List;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;

@Configuration
public class DocumentacionConfig {

    /**
     * Servidor relativo ("/"): Swagger UI lo resuelve contra la URL de donde cargó el
     * documento, así "Try it out" va por el gateway en el Swagger agregado y directo a
     * :8081 en el de este servicio. Se descartó generar la URL con los X-Forwarded-*:
     * Gateway 4.3 no los envía sin trusted-proxies, y confiar en ellos aquí dejaría que
     * cualquiera que llegue al puerto falsifique su IP.
     *
     * El esquema bearerAuth habilita "Authorize" en Swagger UI. Solo funciona por el gateway,
     * que convierte el token en los X-User-*; directo en :8081 el servicio no lee el token.
     * Los endpoints públicos lo quitan con @SecurityRequirements vacío.
     */
    @Bean
    OpenAPI documentacion() {
        return new OpenAPI()
                .info(new Info()
                        .title("identity-service")
                        .version("v1")
                        .description("Usuarios, perfil, login y JWT, recuperación de contraseña, sucursales y tarifas"))
                .servers(List.of(new Server().url("/")))
                .components(new Components().addSecuritySchemes("bearerAuth",
                        new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")))
                .addSecurityItem(new SecurityRequirement().addList("bearerAuth"));
    }
}
