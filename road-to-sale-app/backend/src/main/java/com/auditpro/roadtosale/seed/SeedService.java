package com.auditpro.roadtosale.seed;

import com.auditpro.roadtosale.domain.Dealership;
import com.auditpro.roadtosale.domain.User;
import com.auditpro.roadtosale.repo.DealershipRepository;
import com.auditpro.roadtosale.repo.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Idempotent seed of two dealerships + one user each, for local dev and so the
 * Phase-3 E2E can rely on stable credentials. Safe to run repeatedly.
 *
 * <p>Seed credentials (password is BCrypt-hashed at rest):
 * <ul>
 *   <li>Honda of Fremont — rep1 / password123 (Sam Rep, SALESPERSON)</li>
 *   <li>Honda of Oakland — rep2 / password123 (Riley Rep, SALESPERSON)</li>
 * </ul>
 * rep1 vs rep2 are in different dealerships, enabling the cross-tenant 404 test.
 */
@Service
public class SeedService {

    public static final String FREMONT = "Honda of Fremont";
    public static final String OAKLAND = "Honda of Oakland";
    public static final String DEFAULT_PASSWORD = "password123";

    private final DealershipRepository dealershipRepository;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public SeedService(DealershipRepository dealershipRepository,
                       UserRepository userRepository,
                       PasswordEncoder passwordEncoder) {
        this.dealershipRepository = dealershipRepository;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional
    public void seed() {
        Dealership fremont = upsertDealership(FREMONT);
        Dealership oakland = upsertDealership(OAKLAND);
        upsertUser(fremont, "rep1", "Sam Rep");
        upsertUser(oakland, "rep2", "Riley Rep");
    }

    private Dealership upsertDealership(String name) {
        return dealershipRepository.findByName(name).orElseGet(() -> {
            Dealership d = new Dealership();
            d.setId(java.util.UUID.randomUUID());
            d.setName(name);
            return dealershipRepository.save(d);
        });
    }

    private void upsertUser(Dealership dealership, String username, String displayName) {
        if (userRepository.findByUsername(username).isPresent()) {
            return;
        }
        User u = new User();
        u.setDealershipId(dealership.getId());
        u.setUsername(username);
        u.setName(displayName);
        u.setPasswordHash(passwordEncoder.encode(DEFAULT_PASSWORD));
        u.setRoles(new String[]{"SALESPERSON"});
        userRepository.save(u);
    }
}
