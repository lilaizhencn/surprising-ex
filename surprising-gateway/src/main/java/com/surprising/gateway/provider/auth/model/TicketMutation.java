package com.surprising.gateway.provider.auth.model;

import java.util.List;

public record TicketMutation(SupportTicket ticket, List<SupportTicketNote> notes) {
}
