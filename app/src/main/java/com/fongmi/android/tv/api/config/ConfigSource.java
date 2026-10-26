package com.fongmi.android.tv.api.config;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

/** Resolves untrusted subscriptions without changing the active configuration. */
final class ConfigSource<T> {
    interface Fetcher { String fetch(String url) throws Exception; }
    interface Parser<T> { T parse(String url, String name, JsonObject json) throws Exception; }
    interface DepotConsumer { void accept(String url, String name); }

    private final Fetcher fetcher;
    private final Parser<T> parser;
    private final DepotConsumer depots;

    ConfigSource(Fetcher fetcher, Parser<T> parser, DepotConsumer depots) {
        this.fetcher = fetcher;
        this.parser = parser;
        this.depots = depots;
    }

    T load(String url, String name, String cachedUrl, String cachedName, String cachedJson) throws Exception {
        try {
            return resolve(url, name, new HashSet<>());
        } catch (Exception failure) {
            checkInterrupted();
            if (cachedJson == null || cachedJson.isBlank()) throw failure;
            try {
                JsonObject object = object(cachedJson);
                validate(object);
                return parser.parse(cachedUrl, cachedName, object);
            } catch (Exception cacheFailure) {
                cacheFailure.addSuppressed(failure);
                throw cacheFailure;
            }
        }
    }

    private T resolve(String url, String name, Set<String> path) throws Exception {
        checkInterrupted();
        if (path.size() >= 8 || !path.add(url)) throw new IOException("Cyclic or excessively nested depot");
        try {
            JsonObject object = object(fetcher.fetch(url));
            if (!object.has("urls")) {
                validate(object);
                return parser.parse(url, name, object);
            }
            if (!object.get("urls").isJsonArray() || object.getAsJsonArray("urls").isEmpty())
                throw new IOException("Depot urls must be a non-empty array");
            // Register every valid entry for the existing manual depot picker.
            for (JsonElement item : object.getAsJsonArray("urls")) {
                if (validDepot(item)) {
                    JsonObject depot = item.getAsJsonObject();
                    depots.accept(depot.get("url").getAsString(), depotName(depot));
                }
            }
            IOException failure = new IOException("All depot configurations failed");
            for (JsonElement item : object.getAsJsonArray("urls")) {
                checkInterrupted();
                if (!validDepot(item)) continue;
                JsonObject depot = item.getAsJsonObject();
                try {
                    return resolve(depot.get("url").getAsString(), depotName(depot), path);
                } catch (Exception e) {
                    checkInterrupted();
                    failure.addSuppressed(e);
                }
            }
            throw failure;
        } finally {
            path.remove(url);
        }
    }

    private static JsonObject object(String json) throws IOException {
        JsonElement element = JsonParser.parseString(json);
        if (!element.isJsonObject()) throw new IOException("Configuration must be an object");
        JsonObject object = element.getAsJsonObject();
        if (object.has("msg")) throw new IOException("Configuration returned an error: " + object.get("msg"));
        return object;
    }

    static void validate(JsonObject object) throws IOException {
        if (!object.has("sites") || !object.get("sites").isJsonArray() || object.getAsJsonArray("sites").isEmpty())
            throw new IOException("Configuration sites must be a non-empty array");
        Set<String> keys = new HashSet<>();
        for (JsonElement item : object.getAsJsonArray("sites")) {
            if (!item.isJsonObject()) throw new IOException("Invalid site");
            JsonObject site = item.getAsJsonObject();
            if (!string(site, "key") || !string(site, "api") || !keys.add(site.get("key").getAsString()))
                throw new IOException("Site requires a unique key and an api");
        }
        for (String key : new String[]{"parses", "lives", "flags", "hosts", "ads", "allowedOrigins"}) {
            if (object.has(key) && !object.get(key).isJsonArray()) throw new IOException(key + " must be an array");
        }
        for (String key : new String[]{"spider", "wallpaper", "logo", "notice", "danmaku"}) {
            if (object.has(key) && !object.get(key).isJsonNull() &&
                    (!object.get(key).isJsonPrimitive() || !object.getAsJsonPrimitive(key).isString()))
                throw new IOException(key + " must be a string");
        }
    }

    private static boolean validDepot(JsonElement item) {
        return item.isJsonObject() && string(item.getAsJsonObject(), "url");
    }

    private static String depotName(JsonObject depot) {
        return string(depot, "name") ? depot.get("name").getAsString() : depot.get("url").getAsString();
    }

    private static boolean string(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString() && !value.getAsString().isBlank();
    }

    static void checkInterrupted() throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Configuration load canceled");
    }
}
