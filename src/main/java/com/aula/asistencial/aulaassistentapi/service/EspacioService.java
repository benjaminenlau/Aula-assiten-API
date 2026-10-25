package com.aula.asistencial.aulaassistentapi.service;

import java.util.List;

import com.aula.asistencial.aulaassistentapi.dto.EspacioResponse;
import com.aula.asistencial.aulaassistentapi.dto.RecursoRequest;
import com.aula.asistencial.aulaassistentapi.entity.Espacio;
import com.aula.asistencial.aulaassistentapi.exception.RecursoNoEncontradoException;
import com.aula.asistencial.aulaassistentapi.mapper.AulaMapper;
import com.aula.asistencial.aulaassistentapi.repository.EspacioRepository;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class EspacioService {

    private final EspacioRepository repository;

    public EspacioService(EspacioRepository repository) {
        this.repository = repository;
    }

    public List<EspacioResponse> listar() {
        return repository.findAll(Sort.by("id")).stream().map(AulaMapper::toResponse).toList();
    }

    public EspacioResponse obtener(Long id) {
        return AulaMapper.toResponse(buscar(id));
    }

    @Transactional
    public EspacioResponse crear(RecursoRequest request) {
        return AulaMapper.toResponse(repository.saveAndFlush(new Espacio(request.nombre())));
    }

    @Transactional
    public EspacioResponse actualizar(Long id, RecursoRequest request) {
        Espacio espacio = buscar(id);
        espacio.setNombre(request.nombre());
        return AulaMapper.toResponse(repository.saveAndFlush(espacio));
    }

    @Transactional
    public void eliminar(Long id) {
        repository.delete(buscar(id));
        repository.flush();
    }

    private Espacio buscar(Long id) {
        ValidacionesServicio.idPositivo(id, "id");
        return repository.findById(id)
                .orElseThrow(() -> new RecursoNoEncontradoException("No existe el espacio con id " + id + "."));
    }
}
