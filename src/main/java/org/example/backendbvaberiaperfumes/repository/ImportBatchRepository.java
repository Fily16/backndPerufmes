package org.example.backendbvaberiaperfumes.repository;

import org.example.backendbvaberiaperfumes.model.ImportBatch;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ImportBatchRepository extends JpaRepository<ImportBatch, Long> {
    List<ImportBatch> findBySupplierIdAndStatus(Long supplierId, String status);
}
