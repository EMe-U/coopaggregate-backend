package com.coopaggregate.loss;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LossRepository extends JpaRepository<Loss, Long> {

    List<Loss> findByLotIdOrderByLossDateDescIdDesc(Long lotId);

    @EntityGraph(attributePaths = "lot")
    List<Loss> findByIdIn(Collection<Long> ids);
}
