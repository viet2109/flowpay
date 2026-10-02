package com.flowpay.backend.webhook.api;

import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.annotation.JsonDeserialize;
import tools.jackson.databind.annotation.JsonPOJOBuilder;
import java.util.List;

@JsonDeserialize(builder = UpdateWebhookEndpointRequest.Builder.class)
public record UpdateWebhookEndpointRequest(
        @Size(min = 1, max = 2048) String url,
        @Size(min = 1, max = 6) List<String> events
) {
    // Creator null handling also applies to missing record fields. A builder lets
    // PATCH omit a field while still rejecting an explicitly supplied null.
    @JsonPOJOBuilder(withPrefix = "")
    public static final class Builder {
        private String url;
        private List<String> events;

        @JsonSetter(nulls = Nulls.FAIL)
        public Builder url(String value) {
            url = value;
            return this;
        }

        @JsonSetter(nulls = Nulls.FAIL, contentNulls = Nulls.FAIL)
        public Builder events(List<String> value) {
            events = value;
            return this;
        }

        public UpdateWebhookEndpointRequest build() {
            return new UpdateWebhookEndpointRequest(url, events);
        }
    }
}
