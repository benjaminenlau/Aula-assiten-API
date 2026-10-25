package com.aula.asistencial.aulaassistentapi.controller;

import java.net.URI;
import java.util.List;

import com.aula.asistencial.aulaassistentapi.dto.DocenteResponse;
import com.aula.asistencial.aulaassistentapi.dto.RecursoRequest;
import com.aula.asistencial.aulaassistentapi.service.DocenteService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

@RestController
@RequestMapping("/api/docentes")
public class DocenteController {

    private final DocenteService servicio;

    public DocenteController(DocenteService servicio) {
        this.servicio = servicio;
    }

    @GetMapping
    public List<DocenteResponse> listar() {
        return servicio.listar();
    }

    @GetMapping("/{id}")
    public DocenteResponse obtener(@PathVariable @Positive Long id) {
        return servicio.obtener(id);
    }

    @PostMapping
    public ResponseEntity<DocenteResponse> crear(@Valid @RequestBody RecursoRequest solicitud) {
        DocenteResponse respuesta = servicio.crear(solicitud);
        URI ubicacion = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(respuesta.id())
                .toUri();
        return ResponseEntity.created(ubicacion).body(respuesta);
    }

    @PutMapping("/{id}")
    public DocenteResponse actualizar(
            @PathVariable @Positive Long id,
            @Valid @RequestBody RecursoRequest solicitud) {
        return servicio.actualizar(id, solicitud);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> eliminar(@PathVariable @Positive Long id) {
        servicio.eliminar(id);
        return ResponseEntity.noContent().build();
    }
}
