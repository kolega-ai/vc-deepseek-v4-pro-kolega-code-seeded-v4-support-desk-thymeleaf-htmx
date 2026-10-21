package com.relaydesk.channel.repo;

import com.relaydesk.channel.domain.Category;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CategoryRepository extends JpaRepository<Category, UUID> {
    List<Category> findByActiveTrueOrderByNameAsc();
    Optional<Category> findByNameIgnoreCase(String name);
    boolean existsByNameIgnoreCase(String name);
}
