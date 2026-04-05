package com.ebithex.merchant.infrastructure;

import com.ebithex.merchant.domain.StaffUser;
import com.ebithex.shared.security.Role;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface StaffUserRepository extends JpaRepository<StaffUser, UUID> {
    Optional<StaffUser> findByEmail(String email);
    boolean existsByEmail(String email);
    Page<StaffUser> findByRole(Role role, Pageable pageable);
}