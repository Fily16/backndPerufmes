package org.example.backendbvaberiaperfumes.repository;

import org.example.backendbvaberiaperfumes.model.Supplier;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SupplierRepository extends JpaRepository<Supplier, Long> {
    Optional<Supplier> findByName(String name);
}
