package com.aula.asistencial.aulaassistentapi.service;

import java.util.List;

import com.aula.asistencial.aulaassistentapi.dto.DocenteResponse;
import com.aula.asistencial.aulaassistentapi.dto.RecursoRequest;
import com.aula.asistencial.aulaassistentapi.entity.Docente;
import com.aula.asistencial.aulaassistentapi.exception.RecursoNoEncontradoException;
import com.aula.asistencial.aulaassistentapi.mapper.AulaMapper;
import com.aula.asistencial.aulaassistentapi.repository.DocenteRepository;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class DocenteService {

    private final DocenteRepository repository;

    public DocenteService(DocenteRepository repository) {
        this.repository = repository;
    }

    public List<DocenteResponse> listar() {
        return repository.findAll(Sort.by("id")).stream().map(AulaMapper::toResponse).toList();
    }

    public DocenteResponse obtener(Long id) {
        return AulaMapper.toResponse(buscar(id));
    }

    @Transactional
    public DocenteResponse crear(RecursoRequest request) {
        return AulaMapper.toResponse(repository.saveAndFlush(new Docente(request.nombre())));
    }

    @Transactional
    public DocenteResponse actualizar(Long id, RecursoRequest request) {
        Docente docente = buscar(id);
        docente.setNombre(request.nombre());
        return AulaMapper.toResponse(repository.saveAndFlush(docente));
    }

    @Transactional
    public void eliminar(Long id) {
        repository.delete(buscar(id));
        repository.flush();
    }

    private Docente buscar(Long id) {
        ValidacionesServicio.idPositivo(id, "id");
        return repository.findById(id)
                .orElseThrow(() -> new RecursoNoEncontradoException("No existe el docente con id " + id + "."));
    }
}
