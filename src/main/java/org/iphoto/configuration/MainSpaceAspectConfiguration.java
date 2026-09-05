package org.iphoto.configuration;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.hibernate.Session;
import org.iphoto.entities.SpaceEntity;
import org.iphoto.entities.UserEntity;
import org.iphoto.repository.UserRepository;
import org.iphoto.service.SpaceService;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.NoSuchElementException;

import static org.iphoto.configuration.ThreadPoolConfiguration.*;

@Aspect
@Component
@RequiredArgsConstructor
@Slf4j
public class MainSpaceAspectConfiguration {

    private final EntityManager entityManager;
    private final UserRepository userRepository;
    private final SpaceService spaceService;


        // Intercepts all execution of Spring Data Repositories before query execution
        @Before("@annotation(org.iphoto.configuration.MainSpaceLimited)")
        public void enableSpaceFilter(JoinPoint jp) {
            if (!Thread.currentThread().getName().startsWith(BACKEND_TASK) && !Thread.currentThread().getName().startsWith(HEAVY_TASK)) {
                String methodName = jp.getSignature().getName();
                log.debug("Executing method: {}", methodName);
                Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
                String username = authentication.getName();
                UserEntity userEntity = userRepository.findByUsername(username)
                        .orElseThrow(() -> new NoSuchElementException("User not found: " + username));

                if (username != null) {
                    SpaceEntity spaceEntity = spaceService.getDefaultSpaceForUser(userEntity);
                    Session session = entityManager.unwrap(Session.class);
                    // Enable the filter and bind the logged-in user ID
                    session.enableFilter("spaceFilter")
                            .setParameter("currentSpaceId", spaceEntity.getId());
                }
            }

    }
}
