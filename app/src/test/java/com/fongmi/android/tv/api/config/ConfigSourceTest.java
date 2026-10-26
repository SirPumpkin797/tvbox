package com.fongmi.android.tv.api.config;

import org.junit.Test;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import static org.junit.Assert.*;

public class ConfigSourceTest {
    private static final String GOOD = "{\"sites\":[{\"key\":\"test\",\"api\":\"csp_Test\",\"type\":3}]}";
    private static final String DEPOT = "{\"urls\":[{\"url\":\"first\"},{\"url\":\"second\"},{\"url\":\"third\"}]}";
    private final Map<String, String> responses = new HashMap<>();
    private final List<String> requests = new ArrayList<>();
    private final List<String> registered = new ArrayList<>();

    private ConfigSource<String> source() {
        return new ConfigSource<>(url -> {
            requests.add(url);
            if (!responses.containsKey(url)) throw new SocketTimeoutException("timed out");
            return responses.get(url);
        }, (url, name, object) -> {
            if (object.has("badOptionalField")) throw new IllegalArgumentException("Malformed optional field");
            return url;
        }, (url, name) -> registered.add(url));
    }

    private String load() throws Exception {
        return source().load("root", "root", null, null, null);
    }

    @Test public void firstSuccessStopsAndRegistersAllForManualSelection() throws Exception {
        responses.put("root", DEPOT);
        responses.put("first", GOOD);
        assertEquals("first", load());
        assertEquals(List.of("root", "first"), requests);
        assertEquals(List.of("first", "second", "third"), registered);
    }

    @Test public void timeoutThenInvalidThenSuccessInArrayOrder() throws Exception {
        responses.put("root", DEPOT);
        responses.put("second", "{\"sites\":[]}");
        responses.put("third", GOOD);
        assertEquals("third", load());
        assertEquals(List.of("root", "first", "second", "third"), requests);
    }

    @Test public void eachColdLoadFetchesRootAndRestartsAtFirst() throws Exception {
        responses.put("root", DEPOT);
        responses.put("second", GOOD);
        assertEquals("second", load());
        requests.clear();
        responses.put("first", GOOD);
        assertEquals("first", load());
        assertEquals(List.of("root", "first"), requests);
    }

    @Test public void usesCacheOnlyAfterAllRepositoriesFail() throws Exception {
        responses.put("root", DEPOT);
        assertEquals("cached", source().load("root", "root", "cached", "cached", GOOD));
        assertEquals(List.of("root", "first", "second", "third"), requests);
    }

    @Test public void unavailableSubscriptionUsesCacheWithoutRequestingCachedUrl() throws Exception {
        assertEquals("cached", source().load("root", "root", "cached", "cached", GOOD));
        assertEquals(List.of("root"), requests);
    }

    @Test public void optionalParsingFailureTriesNextRepository() throws Exception {
        responses.put("root", DEPOT);
        responses.put("first", GOOD.replace("{\"sites\"", "{\"badOptionalField\":true,\"sites\""));
        responses.put("second", GOOD);
        assertEquals("second", load());
    }

    @Test public void manualSingleRepositoryStillWorks() throws Exception {
        responses.put("manual", GOOD);
        assertEquals("manual", source().load("manual", "manual", null, null, null));
        assertEquals(List.of("manual"), requests);
    }

    @Test public void cyclicNestedDepotFallsThrough() throws Exception {
        responses.put("root", DEPOT);
        responses.put("first", "{\"urls\":[{\"url\":\"root\"}]}");
        responses.put("second", GOOD);
        assertEquals("second", load());
        assertEquals(List.of("root", "first", "second"), requests);
    }

    @Test public void malformedEntriesAreSkippedWithoutReordering() throws Exception {
        responses.put("root", "{\"urls\":[null,{},42,{\"url\":\"\"},{\"url\":\"second\"}]}");
        responses.put("second", GOOD);
        assertEquals("second", load());
        assertEquals(List.of("root", "second"), requests);
    }

    @Test public void invalidConfigsNeverReachPublication() {
        for (String invalid : List.of("null", "[]", "<html>error</html>", "{}", "{\"msg\":\"error\"}",
                "{\"sites\":[]}", "{\"sites\":[{}]}", "{\"sites\":[null]}",
                "{\"sites\":[{\"key\":\"a\",\"api\":\"b\"}],\"parses\":{}}", "{\"urls\":{}}")) {
            responses.put("root", invalid);
            assertThrows(Exception.class, this::load);
        }
    }

    @Test public void corruptCacheReportsFailure() {
        assertThrows(Exception.class, () -> source().load("root", "root", "cache", "cache", "{}"));
    }

    @Test public void interruptionDoesNotFallBackOrFetchAnotherRepository() {
        ConfigSource<String> source = new ConfigSource<>(url -> {
            Thread.currentThread().interrupt();
            throw new IOException("canceled");
        }, (url, name, object) -> failResult(), (url, name) -> {});
        try {
            assertThrows(InterruptedException.class, () -> source.load("root", "root", "cache", "cache", GOOD));
        } finally {
            Thread.interrupted();
        }
    }

    private String failResult() {
        fail("Canceled load must not publish cache");
        return "";
    }
}
