package org.example.backendbvaberiaperfumes.repository;

import org.example.backendbvaberiaperfumes.model.SupplierConstraint;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SupplierConstraintRepository extends JpaRepository<SupplierConstraint, Long> {

    List<SupplierConstraint> findBySupplier_IdAndActiveTrue(Long supplierId);

    List<SupplierConstraint> findBySupplier_Id(Long supplierId);

    List<SupplierConstraint> findByActiveTrue();

    boolean existsBySupplier_IdAndType(Long supplierId, String type);

    void deleteBySupplier_Id(Long supplierId);
}
