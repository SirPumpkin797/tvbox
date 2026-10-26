package com.fongmi.android.tv.api.loader;

import android.content.Context;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.utils.DependencyTrust;
import com.fongmi.android.tv.utils.Download;
import com.fongmi.android.tv.utils.UrlUtil;
import com.github.catvod.crawler.Spider;
import com.github.catvod.crawler.SpiderDebug;
import com.github.catvod.crawler.SpiderNull;
import com.github.catvod.utils.Path;
import com.github.catvod.utils.Util;

import org.json.JSONObject;

import java.io.File;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

import dalvik.system.DexClassLoader;

public class JarLoader {

    private final ConcurrentHashMap<String, DexClassLoader> loaders;
    private final ConcurrentHashMap<String, Method> methods;
    private final ConcurrentHashMap<String, Spider> spiders;
    private final ConcurrentHashMap<String, Object> locks;
    private volatile String recent;
    private volatile int generation;

    public JarLoader() {
        loaders = new ConcurrentHashMap<>();
        methods = new ConcurrentHashMap<>();
        spiders = new ConcurrentHashMap<>();
        locks = new ConcurrentHashMap<>();
    }

    public synchronized void clear() {
        generation++;
        spiders.values().forEach(Spider::destroy);
        loaders.clear();
        methods.clear();
        spiders.clear();
        locks.clear();
        recent = null;
        File optRoot = new File(App.get().getCodeCacheDir(), "dexopt");
        if (optRoot.exists()) for (File child : optRoot.listFiles() == null ? new File[0] : optRoot.listFiles()) Path.clear(child);
    }

    public void setRecent(String recent) {
        this.recent = loaders.containsKey(recent) ? recent : null;
    }

    private void load(String key, File file, int expectedGeneration) {
        if (generation != expectedGeneration || Thread.currentThread().isInterrupted()) return;
        if (!Path.exists(file) || !file.setReadOnly()) return;
        File optDir = new File(App.get().getCodeCacheDir(), "dexopt" + File.separator + key);
        if (!optDir.exists()) optDir.mkdirs();
        DexClassLoader loader = new DexClassLoader(file.getAbsolutePath(), optDir.getAbsolutePath(), null, App.get().getClassLoader());
        invokeInit(loader);
        synchronized (this) {
            if (generation != expectedGeneration || Thread.currentThread().isInterrupted()) return;
            invokeProxy(key, loader);
            loaders.put(key, loader);
        }
    }

    private void invokeInit(DexClassLoader loader) {
        try {
            Class<?> clz = loader.loadClass("com.github.catvod.spider.Init");
            Method method = clz.getMethod("init", Context.class);
            method.invoke(clz, App.get());
        } catch (Throwable e) {
            SpiderDebug.log(e);
        }
    }

    private void invokeProxy(String key, DexClassLoader loader) {
        try {
            Class<?> clz = loader.loadClass("com.github.catvod.spider.Proxy");
            Method method = clz.getMethod("proxy", Map.class);
            methods.put(key, method);
        } catch (Throwable e) {
            SpiderDebug.log(e);
        }
    }

    public void parseJar(String key, String jar) {
        int expectedGeneration = generation;
        if (loaders.containsKey(key)) return;
        boolean asset = jar.startsWith("assets");
        if (asset) jar = UrlUtil.convert(jar);
        Object lock = locks.computeIfAbsent(key, k -> new Object());
        synchronized (lock) {
            if (loaders.containsKey(key)) return;
            String[] texts = splitHash(jar);
            jar = texts[0];
            String hashType = texts[1];
            String hash = texts[2];
            if (asset) {
                File file = Download.create(jar, Path.jar(jar)).get();
                if (Path.exists(file) && (hash.isEmpty() || verify(file, hashType, hash))) load(key, file, expectedGeneration);
            } else if (jar.startsWith("https")) {
                if (hash.isEmpty()) {
                    SpiderDebug.log("jar", "rejected remote jar without ;sha256;/;md5; key=%s url=%s", key, jar);
                    return;
                }
                File file = Download.create(jar, Path.jar(jar)).get();
                if (!verify(file, hashType, hash)) {
                    Path.clear(file);
                    SpiderDebug.log("jar", "hash mismatch key=%s url=%s type=%s", key, jar, hashType);
                    return;
                }
                if (!DependencyTrust.confirm("JAR", jar, hashType, hash, file)) {
                    SpiderDebug.log("jar", "remote jar not trusted key=%s url=%s", key, jar);
                    return;
                }
                load(key, file, expectedGeneration);
            } else if (jar.startsWith("file")) {
                if (hash.isEmpty()) {
                    SpiderDebug.log("jar", "rejected file jar without ;sha256;/;md5; key=%s url=%s", key, jar);
                    return;
                }
                File file = Path.local(jar);
                if (!Path.exists(file) || !verify(file, hashType, hash)) {
                    SpiderDebug.log("jar", "file jar missing or hash mismatch key=%s url=%s", key, jar);
                    return;
                }
                if (!DependencyTrust.confirm("JAR", jar, hashType, hash, file)) {
                    SpiderDebug.log("jar", "file jar not trusted key=%s url=%s", key, jar);
                    return;
                }
                load(key, file, expectedGeneration);
            } else if (jar.startsWith("http")) {
                SpiderDebug.log("jar", "rejected cleartext http jar key=%s url=%s", key, jar);
            } else {
                SpiderDebug.log("jar", "skipped unrecognized jar key=%s url=%s", key, jar);
            }
        }
    }

    private String[] splitHash(String jar) {
        String[] sha = jar.split(";sha256;", 2);
        if (sha.length > 1) return new String[]{sha[0], "sha256", sha[1].trim()};
        String[] md5 = jar.split(";md5;", 2);
        if (md5.length > 1) return new String[]{md5[0], "md5", md5[1].trim()};
        return new String[]{jar, "", ""};
    }

    private boolean verify(File file, String type, String hash) {
        if (file == null || !Path.exists(file) || hash == null || hash.startsWith("http")) return false;
        if ("sha256".equalsIgnoreCase(type)) return Util.equalsSha256(file, hash);
        if ("md5".equalsIgnoreCase(type)) return Util.md5(file).equalsIgnoreCase(hash);
        return false;
    }

    public DexClassLoader dex(String jar) {
        try {
            String jaKey = Util.md5(jar);
            parseJar(jaKey, jar);
            return loaders.get(jaKey);
        } catch (Throwable e) {
            SpiderDebug.log(e);
            return null;
        }
    }

    public Spider getSpider(String key, String api, String ext, String jar) {
        int expectedGeneration = generation;
        String jaKey = Util.md5(jar);
        String spKey = expectedGeneration + ":" + jaKey + key + Util.md5(api + "\n" + ext);
        Spider result = spiders.computeIfAbsent(spKey, k -> {
            try {
                parseJar(jaKey, jar);
                DexClassLoader loader = loaders.get(jaKey);
                if (loader == null) return null;
                Spider spider = (Spider) loader.loadClass("com.github.catvod.spider." + api.split("csp_")[1]).newInstance();
                spider.siteKey = key;
                spider.init(App.get(), ext);
                return spider;
            } catch (Throwable e) {
                SpiderDebug.log(e);
                return null;
            }
        });
        if (generation != expectedGeneration) {
            spiders.remove(spKey);
            return new SpiderNull();
        }
        if (result != null) setRecent(jaKey);
        return result == null ? new SpiderNull() : result;
    }

    private DexClassLoader requireRecentLoader() {
        DexClassLoader loader = loaders.get(recent);
        if (loader == null) throw new IllegalStateException("No jar loaded for recent key: " + recent);
        return loader;
    }

    public JSONObject jsonExt(String key, LinkedHashMap<String, String> jxs, String url) throws Throwable {
        Class<?> clz = requireRecentLoader().loadClass("com.github.catvod.parser.Json" + key);
        Method method = clz.getMethod("parse", LinkedHashMap.class, String.class);
        return (JSONObject) method.invoke(null, jxs, url);
    }

    public JSONObject jsonExtMix(String flag, String key, String name, LinkedHashMap<String, HashMap<String, String>> jxs, String url) throws Throwable {
        Class<?> clz = requireRecentLoader().loadClass("com.github.catvod.parser.Mix" + key);
        Method method = clz.getMethod("parse", LinkedHashMap.class, String.class, String.class, String.class);
        return (JSONObject) method.invoke(null, jxs, name, flag, url);
    }

    public Object[] proxy(Map<String, String> params) throws Exception {
        Method method = recent != null ? methods.get(recent) : null;
        Object[] result = proxyInvoke(method, params);
        if (result != null) return result;
        return tryOthers(params);
    }

    private Object[] tryOthers(Map<String, String> p) {
        return methods.entrySet().stream().filter(e -> !e.getKey().equals(recent)).map(e -> proxyInvoke(e.getValue(), p)).filter(Objects::nonNull).findFirst().orElse(null);
    }

    private Object[] proxyInvoke(Method method, Map<String, String> params) {
        try {
            return method == null ? null : (Object[]) method.invoke(null, params);
        } catch (Throwable e) {
            SpiderDebug.log(e);
            return null;
        }
    }
}
