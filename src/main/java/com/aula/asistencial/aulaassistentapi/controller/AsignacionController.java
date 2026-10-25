package com.aula.asistencial.aulaassistentapi.controller;

import java.net.URI;
import java.time.LocalDateTime;
import java.util.List;

import com.aula.asistencial.aulaassistentapi.dto.AsignacionRequest;
import com.aula.asistencial.aulaassistentapi.dto.AsignacionResponse;
import com.aula.asistencial.aulaassistentapi.service.AsignacionService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

@RestController
@RequestMapping("/api/asignaciones")
public class AsignacionController {

    private final AsignacionService servicio;

    public AsignacionController(AsignacionService servicio) {
        this.servicio = servicio;
    }

    @GetMapping
    public List<AsignacionResponse> listar(
            @RequestParam(required = false) @Positive Long espacioId,
            @RequestParam(required = false) @Positive Long docenteId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
            LocalDateTime desde,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
            LocalDateTime hasta) {
        return servicio.listar(espacioId, docenteId, desde, hasta);
    }

    @GetMapping("/{id}")
    public AsignacionResponse obtener(@PathVariable @Positive Long id) {
        return servicio.obtener(id);
    }

    @PostMapping
    public ResponseEntity<AsignacionResponse> crear(@Valid @RequestBody AsignacionRequest solicitud) {
        AsignacionResponse respuesta = servicio.crear(solicitud);
        URI ubicacion = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(respuesta.id())
                .toUri();
        return ResponseEntity.created(ubicacion).body(respuesta);
    }

    @PutMapping("/{id}")
    public AsignacionResponse actualizar(
            @PathVariable @Positive Long id,
            @Valid @RequestBody AsignacionRequest solicitud) {
        return servicio.actualizar(id, solicitud);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> eliminar(@PathVariable @Positive Long id) {
        servicio.eliminar(id);
        return ResponseEntity.noContent().build();
    }
}
