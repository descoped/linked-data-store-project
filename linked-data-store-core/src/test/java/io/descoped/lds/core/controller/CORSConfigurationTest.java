package io.descoped.lds.core.controller;

import io.descoped.lds.test.ConfigurationOverride;
import io.descoped.lds.test.client.TestClient;
import io.descoped.lds.test.server.TestServerListener;
import org.testng.annotations.Listeners;
import org.testng.annotations.Test;

import javax.inject.Inject;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The lists of the CORS settings are read as the configuration writes a list: an entry is what is between two commas,
 * without the blanks around it.
 */
@Listeners(TestServerListener.class)
public class CORSConfigurationTest {

    @Inject
    private TestClient client;

    @Test
    @ConfigurationOverride({
            "http.cors.allow.origin", "https://other.example, localhost",
            "http.cors.allow.methods", "GET, PUT",
            "http.cors.allow.header", "Content-Type, X-Lds-Test"
    })
    public void thatTheEntriesOfTheCorsListsAreTrimmed() {
        HttpResponse<String> response = client.options("/data/provisionagreement/1",
                "Access-Control-Request-Method", "PUT",
                "Access-Control-Request-Headers", "X-Lds-Test").response();

        assertThat(response.statusCode()).isEqualTo(204);
        assertThat(response.headers().firstValue("Access-Control-Allow-Origin")).hasValue("localhost");
        assertThat(response.headers().firstValue("Access-Control-Allow-Methods").orElseThrow().split(","))
                .containsExactlyInAnyOrder("GET", "PUT");
        assertThat(response.headers().firstValue("Access-Control-Allow-Headers").orElseThrow().split(","))
                .containsExactlyInAnyOrder("Content-Type", "X-Lds-Test");
    }
}
