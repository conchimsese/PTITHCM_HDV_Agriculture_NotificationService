package vn.soa.notification.dto;

import java.util.UUID;

public record UserAccessStateDto(
        UUID accountId,
        String state,
        String role
) {
    public boolean isActive() {
        return "ACTIVE".equalsIgnoreCase(state);
    }
}
