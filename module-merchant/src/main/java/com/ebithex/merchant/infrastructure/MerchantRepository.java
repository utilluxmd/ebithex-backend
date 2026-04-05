package com.ebithex.merchant.infrastructure;

import com.ebithex.merchant.domain.Merchant;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface MerchantRepository extends JpaRepository<Merchant, UUID> {
    Optional<Merchant> findByEmail(String email);
    boolean existsByEmail(String email);
    Page<Merchant> findByCountry(String country, Pageable pageable);
    // Les recherches par clé API sont désormais dans ApiKeyRepository (V18).
}
