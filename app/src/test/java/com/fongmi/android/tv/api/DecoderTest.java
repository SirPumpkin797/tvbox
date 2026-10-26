package com.fongmi.android.tv.api;

import org.junit.Test;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.SocketPolicy;
import static org.junit.Assert.*;

public class DecoderTest {
    @Test public void stalledBodyTimesOutAndNextCallSucceeds() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));
            server.enqueue(new MockResponse().setBody("{\"sites\":[]}"));
            OkHttpClient client = new OkHttpClient();
            String url = server.url("/config").toString();
            long start = System.nanoTime();
            assertThrows(IOException.class, () -> Decoder.getConfigJson(url, "test", client));
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            assertTrue("Timeout must bound the entire request: " + elapsedMs, elapsedMs < 8000);
            assertEquals("{\"sites\":[]}", Decoder.getConfigJson(url, "test", client));
        }
    }

    @Test public void rejectsHttpErrorEvenWithValidJsonBody() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setResponseCode(503).setBody("{\"sites\":[]}"));
            assertThrows(IOException.class, () -> Decoder.getConfigJson(server.url("/").toString(), "test", new OkHttpClient()));
        }
    }
}
