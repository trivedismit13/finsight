package com.finsight.security;

import com.finsight.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.util.Collections;

@Service
@RequiredArgsConstructor
public class CustomUserDetailsService implements UserDetailsService {

    private final UserRepository userRepository;

    @Override
    public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
        com.finsight.model.User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new UsernameNotFoundException("User not found with email: " + email));

        return new CustomUserDetails(
                user.getEmail(),
                user.getPassword(),
                user.isActive(),
                true,
                true,
                user.getLockedUntil() == null || user.getLockedUntil().isBefore(java.time.LocalDateTime.now()),
                Collections.singletonList(new SimpleGrantedAuthority("ROLE_" + user.getRole().name())),
                user.getUserId()
        );
    }
}
