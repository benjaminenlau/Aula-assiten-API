package com.aula.asistencial.aulaassistentapi.security;

import java.net.URI;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

@RestController
@RequestMapping("/api/usuarios")
public class UsuarioController {

    private final AuthService auth;

    public UsuarioController(AuthService auth) { this.auth = auth; }

    @GetMapping
    public ResponseEntity<List<AuthDtos.Usuario>> listar() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(auth.listarUsuarios());
    }

    @PostMapping
    public ResponseEntity<AuthDtos.Usuario> crear(@Valid @RequestBody AuthDtos.CrearUsuarioRequest request) {
        var usuario = auth.crearUsuario(request);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest().path("/{id}")
                .buildAndExpand(usuario.id()).toUri();
        return ResponseEntity.created(location).cacheControl(CacheControl.noStore()).body(usuario);
    }

    @PatchMapping("/{id}/estado")
    public ResponseEntity<AuthDtos.Usuario> estado(@AuthenticationPrincipal AuthDtos.Principal actor,
            @PathVariable @Positive long id, @Valid @RequestBody AuthDtos.EstadoUsuarioRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(auth.cambiarEstado(actor.id(), id, request.activo()));
    }

    @PutMapping("/{id}/password")
    public ResponseEntity<Void> password(@AuthenticationPrincipal AuthDtos.Principal actor,
            @PathVariable @Positive long id, @Valid @RequestBody AuthDtos.CambiarPasswordRequest request) {
        auth.cambiarPassword(actor.id(), id, request.password());
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }
}
