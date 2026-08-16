package com.surprising.aeron.client;

import com.surprising.aeron.protocol.CoreResponse;
import io.aeron.Publication;
import java.util.Objects;
import java.util.UUID;

public sealed interface CoreCommandOutcome
        permits CoreCommandOutcome.Terminal, CoreCommandOutcome.ResultUnknown, CoreCommandOutcome.NotAccepted {

    record Terminal(CoreResponse response) implements CoreCommandOutcome {
        public Terminal {
            Objects.requireNonNull(response, "response");
        }
    }

    record ResultUnknown(UUID originalCommandId) implements CoreCommandOutcome {
        public ResultUnknown {
            Objects.requireNonNull(originalCommandId, "originalCommandId");
        }
    }

    record NotAccepted(NotAcceptedReason reason, long rawOfferResult) implements CoreCommandOutcome {
        public NotAccepted {
            Objects.requireNonNull(reason, "reason");
        }
    }

    enum NotAcceptedReason {
        CLIENT_BACKPRESSURED,
        NOT_CONNECTED,
        ADMIN_ACTION,
        CLOSED,
        MAX_POSITION_EXCEEDED,
        UNKNOWN
    }

    final class NotAcceptedException extends RuntimeException {
        private final NotAccepted rejection;

        public NotAcceptedException(NotAccepted rejection) {
            super("Aeron request was not accepted: " + Objects.requireNonNull(rejection, "rejection").reason()
                    + " (offer=" + rejection.rawOfferResult() + ')');
            this.rejection = rejection;
        }

        public NotAccepted rejection() {
            return rejection;
        }
    }

    static NotAccepted notAccepted(long rawOfferResult) {
        NotAcceptedReason reason;
        if (rawOfferResult == Publication.BACK_PRESSURED) {
            reason = NotAcceptedReason.CLIENT_BACKPRESSURED;
        } else if (rawOfferResult == Publication.NOT_CONNECTED) {
            reason = NotAcceptedReason.NOT_CONNECTED;
        } else if (rawOfferResult == Publication.ADMIN_ACTION) {
            reason = NotAcceptedReason.ADMIN_ACTION;
        } else if (rawOfferResult == Publication.CLOSED) {
            reason = NotAcceptedReason.CLOSED;
        } else if (rawOfferResult == Publication.MAX_POSITION_EXCEEDED) {
            reason = NotAcceptedReason.MAX_POSITION_EXCEEDED;
        } else {
            reason = NotAcceptedReason.UNKNOWN;
        }
        return new NotAccepted(reason, rawOfferResult);
    }
}
