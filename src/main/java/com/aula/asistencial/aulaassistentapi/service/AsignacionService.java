package com.aula.asistencial.aulaassistentapi.service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import com.aula.asistencial.aulaassistentapi.dto.AsignacionRequest;
import com.aula.asistencial.aulaassistentapi.dto.AsignacionResponse;
import com.aula.asistencial.aulaassistentapi.entity.Asignacion;
import com.aula.asistencial.aulaassistentapi.entity.Docente;
import com.aula.asistencial.aulaassistentapi.entity.Espacio;
import com.aula.asistencial.aulaassistentapi.exception.RecursoNoEncontradoException;
import com.aula.asistencial.aulaassistentapi.exception.SolicitudInvalidaException;
import com.aula.asistencial.aulaassistentapi.mapper.AulaMapper;
import com.aula.asistencial.aulaassistentapi.repository.AsignacionRepository;
import com.aula.asistencial.aulaassistentapi.repository.DocenteRepository;
import com.aula.asistencial.aulaassistentapi.repository.EspacioRepository;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class AsignacionService {

    private final AsignacionRepository repository;
    private final EspacioRepository espacioRepository;
    private final DocenteRepository docenteRepository;

    public AsignacionService(AsignacionRepository repository, EspacioRepository espacioRepository,
                             DocenteRepository docenteRepository) {
        this.repository = repository;
        this.espacioRepository = espacioRepository;
        this.docenteRepository = docenteRepository;
    }

    public List<AsignacionResponse> listar(Long espacioId, Long docenteId, LocalDateTime desde,
                                          LocalDateTime hasta) {
        if (espacioId != null) {
            ValidacionesServicio.idPositivo(espacioId, "espacioId");
        }
        if (docenteId != null) {
            ValidacionesServicio.idPositivo(docenteId, "docenteId");
        }
        if ((desde == null) != (hasta == null)) {
            throw new SolicitudInvalidaException("desde y hasta deben enviarse juntos.");
        }
        if (desde != null) {
            ValidacionesServicio.intervalo(desde, hasta, "desde", "hasta");
        }

        Specification<Asignacion> filtros = (root, query, builder) -> {
            List<Predicate> condiciones = new ArrayList<>();
            if (espacioId != null) {
                condiciones.add(builder.equal(root.get("espacio").get("id"), espacioId));
            }
            if (docenteId != null) {
                condiciones.add(builder.equal(root.get("docente").get("id"), docenteId));
            }
            if (desde != null) {
                // Solapamiento de intervalos [inicio, fin): los extremos contiguos no se cruzan.
                condiciones.add(builder.lessThan(root.<LocalDateTime>get("inicio"), hasta));
                condiciones.add(builder.greaterThan(root.<LocalDateTime>get("fin"), desde));
            }
            return builder.and(condiciones.toArray(Predicate[]::new));
        };
        return repository.findAll(filtros, Sort.by("inicio", "id"))
                .stream().map(AulaMapper::toResponse).toList();
    }

    public AsignacionResponse obtener(Long id) {
        return AulaMapper.toResponse(buscar(id));
    }

    @Transactional
    public AsignacionResponse crear(AsignacionRequest request) {
        validar(request);
        Asignacion asignacion = new Asignacion(buscarEspacio(request.espacioId()),
                buscarDocente(request.docenteId()), request.inicio(), request.fin());
        return AulaMapper.toResponse(repository.saveAndFlush(asignacion));
    }

    @Transactional
    public AsignacionResponse actualizar(Long id, AsignacionRequest request) {
        validar(request);
        Asignacion asignacion = buscar(id);
        asignacion.setEspacio(buscarEspacio(request.espacioId()));
        asignacion.setDocente(buscarDocente(request.docenteId()));
        asignacion.setInicio(request.inicio());
        asignacion.setFin(request.fin());
        return AulaMapper.toResponse(repository.saveAndFlush(asignacion));
    }

    @Transactional
    public void eliminar(Long id) {
        repository.delete(buscar(id));
        repository.flush();
    }

    private void validar(AsignacionRequest request) {
        ValidacionesServicio.idPositivo(request.espacioId(), "espacioId");
        ValidacionesServicio.idPositivo(request.docenteId(), "docenteId");
        ValidacionesServicio.intervalo(request.inicio(), request.fin(), "inicio", "fin");
    }

    private Asignacion buscar(Long id) {
        ValidacionesServicio.idPositivo(id, "id");
        return repository.findById(id)
                .orElseThrow(() -> new RecursoNoEncontradoException("No existe la asignacion con id " + id + "."));
    }

    private Espacio buscarEspacio(Long id) {
        return espacioRepository.findById(id)
                .orElseThrow(() -> new RecursoNoEncontradoException("No existe el espacio con id " + id + "."));
    }

    private Docente buscarDocente(Long id) {
        return docenteRepository.findById(id)
                .orElseThrow(() -> new RecursoNoEncontradoException("No existe el docente con id " + id + "."));
    }
}
