package com.fongmi.android.tv.api.config;

import android.text.TextUtils;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.api.CspWarmup;
import com.fongmi.android.tv.api.Decoder;
import com.fongmi.android.tv.api.loader.BaseLoader;
import com.fongmi.android.tv.bean.Config;
import com.fongmi.android.tv.bean.Live;
import com.fongmi.android.tv.db.AppDatabase;
import com.fongmi.android.tv.bean.Parse;
import com.fongmi.android.tv.bean.Rule;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.event.ConfigEvent;
import com.fongmi.android.tv.event.RefreshEvent;
import com.fongmi.android.tv.impl.Callback;
import com.fongmi.android.tv.setting.CustomCspSetting;
import com.fongmi.android.tv.utils.UrlUtil;
import com.fongmi.android.tv.web.ext.WebHomeExtensionRegistry;
import com.github.catvod.bean.Doh;
import com.github.catvod.bean.Header;
import com.github.catvod.bean.Proxy;
import com.github.catvod.utils.Json;
import com.github.catvod.crawler.SpiderDebug;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

public class VodConfig extends BaseConfig {

    private static final String TAG = VodConfig.class.getSimpleName();
    public static final String DEFAULT_URL = "https://raw.githubusercontent.com/SirPumpkin797/tvbox-ysc-config-tvbox-autoupdate/main/output/多仓订阅.json";

    private Config requested;
    private boolean startupRequested;
    private JsonObject preparedJson;
    private List<Header> headers;
    private List<Proxy> proxies;
    private List<String> hosts;

    private Site home;
    private String wall;
    private Parse parse;
    private List<Doh> doh;
    private List<Rule> rules;
    private List<Site> sites;
    private List<String> ads;
    private List<String> flags;
    private List<String> allowedOrigins;
    private List<Parse> parses;

    public static VodConfig get() {
        return Loader.INSTANCE;
    }

    public static int getCid() {
        return get().getConfig().getId();
    }

    public static String getUrl() {
        return get().getConfig().getUrl();
    }

    public static String getDesc() {
        return get().getConfig().getDesc();
    }

    public static int getHomeIndex() {
        return get().getSites().indexOf(get().getHome());
    }

    public static boolean hasParse() {
        return !get().getParses().isEmpty();
    }

    public static void load(Config config, Callback callback) {
        get().config(config).load(callback);
    }

    public synchronized VodConfig startup() {
        if (!startupRequested) {
            startupRequested = true;
            requested = defaultConfig();
        } else requested = getConfig();
        return this;
    }

    public VodConfig init() {
        return config(Config.vod());
    }

    public synchronized VodConfig config(Config config) {
        this.requested = config;
        return this;
    }

    @Override
    protected Config requestedConfig() {
        return requested == null ? defaultConfig() : requested;
    }

    public VodConfig clear() {
        ads = null;
        doh = null;
        home = null;
        wall = null;
        parse = null;
        sites = null;
        flags = null;
        rules = null;
        parses = null;
        allowedOrigins = null;
        WebHomeExtensionRegistry.get().setGlobalSources(null, "");
        BaseLoader.get().clear();
        RuleConfig.get().invalidate();
        return this;
    }

    @Override
    protected String getTag() {
        return TAG;
    }

    @Override
    protected Config defaultConfig() {
        return Config.create(VOD).url(DEFAULT_URL).name("默认多仓订阅");
    }

    @Override
    protected void postEvent() {
        super.postEvent();
        ConfigEvent.vod();
    }

    @Override
    protected void load(Config config) throws Throwable {
        apply(prepare(config));
    }

    @Override
    protected void load(Config config, int id) throws Throwable {
        VodConfig prepared = prepare(config);
        synchronized (this) {
            if (!isCurrent(id)) throw new InterruptedException("Superseded configuration");
            apply(prepared);
        }
    }

    private VodConfig prepare(Config requested) throws Exception {
        Config cached = AppDatabase.get().getConfigDao().findLastSuccessfulVod();
        ConfigSource<VodConfig> source = new ConfigSource<>(
                url -> Decoder.getConfigJson(UrlUtil.convert(url), TAG),
                (url, name, object) -> {
                    VodConfig candidate = new VodConfig();
                    candidate.config = Config.find(url, name, VOD);
                    candidate.parseConfig(candidate.config, object.deepCopy());
                    return candidate;
                }, (url, name) -> Config.find(url, name, VOD));
        return source.load(requested.getUrl(), requested.getName(),
                cached == null ? null : cached.getUrl(), cached == null ? null : cached.getName(),
                cached == null ? null : cached.getJson());
    }

    private void apply(VodConfig candidate) throws InterruptedException {
        ConfigSource.checkInterrupted();
        // Keep old spiders available during fetch/validation; reset before publishing a valid replacement.
        if (isLoaded()) BaseLoader.get().clearNow();
        candidate.config.json(candidate.preparedJson.toString()).update();
        home = candidate.home;
        wall = candidate.wall;
        parse = candidate.parse;
        doh = candidate.doh;
        rules = candidate.rules;
        sites = candidate.sites;
        ads = candidate.ads;
        flags = candidate.flags;
        allowedOrigins = candidate.allowedOrigins;
        parses = candidate.parses;
        config = candidate.config;
        setHeaders(candidate.headers);
        setProxy(candidate.proxies);
        setHosts(candidate.hosts);
        RuleConfig.get().invalidate();
        WebHomeExtensionRegistry.get().setGlobalSources(candidate.preparedJson.get("webHomeExtensions"), config.getUrl());
        initWall(config, candidate.preparedJson);
        // Live loading is independent of VOD startup and may download a spider JAR.
        com.fongmi.android.tv.utils.Task.execute(() -> {
            try {
                if (this.config == candidate.config) initLive(candidate.config, candidate.preparedJson);
            } catch (Exception e) {
                SpiderDebug.log(e);
            }
        });
    }

    @Override
    protected boolean isLoaded() {
        return !getSites().isEmpty();
    }

    @Override
    protected void beforeLoad() {
        CspWarmup.reset();
    }

    @Override
    protected void onLoadSuccess() {
        CspWarmup.schedule("vod-config-loaded");
    }

    private void parseConfig(Config config, JsonObject object) throws Exception {
        // Materialize referenced arrays before publication, so cached JSON is self-contained.
        for (String key : new String[]{"headers", "proxy", "rules", "doh"}) {
            if (object.has(key)) object.add(key, configArray(object.get(key)));
        }
        if (object.has("lives")) for (JsonElement live : object.getAsJsonArray("lives")) {
            if (!live.isJsonObject()) throw new IllegalArgumentException("Invalid live entry");
            App.gson().fromJson(live, Live.class);
        }
        initList(object);
        wall = Json.safeString(object, "wallpaper");
        initSite(config, object);
        initParse(config, object);
        config.setLogo(Json.safeString(object, "logo"));
        config.setNotice(Json.safeString(object, "notice"));
        config.setDanmaku(Json.safeString(object, "danmaku"));
        preparedJson = object;
    }

    private JsonArray configArray(JsonElement element) throws Exception {
        if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString())
            element = Json.parse(Decoder.getConfigJson(UrlUtil.convert(element.getAsString()), TAG));
        if (!element.isJsonArray()) throw new IllegalArgumentException("Expected configuration array");
        JsonArray result = new JsonArray();
        for (JsonElement item : element.getAsJsonArray()) {
            if (item.isJsonObject()) result.add(item);
            else if (item.isJsonPrimitive() && item.getAsJsonPrimitive().isString()) {
                JsonElement external = Json.parse(Decoder.getConfigJson(UrlUtil.convert(item.getAsString()), TAG));
                if (!external.isJsonArray()) throw new IllegalArgumentException("Expected referenced array");
                for (JsonElement entry : external.getAsJsonArray()) {
                    if (!entry.isJsonObject()) throw new IllegalArgumentException("Invalid referenced entry");
                    result.add(entry);
                }
            } else throw new IllegalArgumentException("Invalid configuration array entry");
        }
        return result;
    }

    private void initList(JsonObject object) {
        headers = Json.safeListElement(object, "headers").stream().map(e -> App.gson().fromJson(e, Header.class)).toList();
        proxies = Json.safeListElement(object, "proxy").stream().map(e -> App.gson().fromJson(e, Proxy.class)).toList();
        proxies.forEach(Proxy::init);
        setRules(Rule.arrayFrom(fetchArray(object, "rules")));
        setDoh(Json.safeListElement(object, "doh").stream().map(e -> App.gson().fromJson(e, Doh.class)).toList());
        setFlags(Json.safeListString(object, "flags"));
        hosts = Json.safeListString(object, "hosts");
        setAds(Json.safeListString(object, "ads"));
        setAllowedOrigins(Json.safeListString(object, "allowedOrigins"));
    }

    private void initLive(Config config, JsonObject object) {
        if (Json.isEmpty(object, "lives")) return;
        Config temp = Config.find(config, LIVE).save();
        boolean sync = LiveConfig.get().needSync(config.getUrl());
        if (sync) LiveConfig.get().config(temp.update()).parse(object);
    }

    private void initWall(Config config, JsonObject object) {
        if (Json.isEmpty(object, "wallpaper")) return;
        this.wall = Json.safeString(object, "wallpaper");
        Config temp = Config.find(wall, config.getName(), WALL).save();
        boolean sync = WallConfig.get().needSync(wall);
        if (sync) WallConfig.get().config(temp.update());
    }

    private void initSite(Config config, JsonObject object) {
        String spider = UrlUtil.resolve(config.getUrl(), Json.safeString(object, "spider"));
        setSites(Json.safeListElement(object, "sites").stream().map(e -> Site.objectFrom(e, spider)).distinct().collect(Collectors.toCollection(ArrayList::new)));
        if (getSites().stream().anyMatch(site -> site.getKey().isEmpty() || site.getApi().isEmpty()))
            throw new IllegalArgumentException("Invalid site configuration");
        for (Site site : getSites()) {
            String jar = site.getJar();
            if (!TextUtils.isEmpty(jar) && !jar.startsWith("assets://")) site.setJar(UrlUtil.resolve(config.getUrl(), jar));
        }
        Map<String, Site> items = Site.findAll().stream().collect(Collectors.toMap(Site::getKey, Function.identity()));
        getSites().forEach(site -> site.sync(items.get(site.getKey())));
        CustomCspSetting.Result custom = CustomCspSetting.inject(getSites());
        Site home = !custom.home().isEmpty() ? custom.home() : getSites().stream().filter(item -> item.getKey().equals(config.getHome())).findFirst().orElse(getSites().isEmpty() ? new Site() : getSites().get(0));
        setHome(config, home, false);
    }

    private void initParse(Config config, JsonObject object) {
        setParses(Json.safeListElement(object, "parses").stream().map(Parse::objectFrom).distinct().collect(Collectors.toCollection(ArrayList::new)));
        setParse(config, getParses().isEmpty() ? new Parse() : getParses().stream().filter(item -> item.getName().equals(config.getParse())).findFirst().orElse(getParses().get(0)), false);
    }

    public List<Site> getSites() {
        return sites == null ? Collections.emptyList() : sites;
    }

    private void setSites(List<Site> sites) {
        this.sites = sites;
    }

    public List<Parse> getParses() {
        return parses == null ? Collections.emptyList() : parses;
    }

    private void setParses(List<Parse> parses) {
        if (!parses.isEmpty()) parses.add(0, Parse.god());
        this.parses = parses;
    }

    public List<Doh> getDoh() {
        List<Doh> items = Doh.get(App.get());
        if (doh == null) return items;
        items.removeAll(doh);
        items.addAll(doh);
        return items;
    }

    private void setDoh(List<Doh> doh) {
        this.doh = doh;
    }

    public List<Rule> getRules() {
        return rules == null ? Collections.emptyList() : rules;
    }

    private void setRules(List<Rule> rules) {
        this.rules = rules;
    }

    public List<Parse> getParses(int type) {
        return getParses().stream().filter(item -> item.getType() == type).toList();
    }

    public List<Parse> getParses(int type, String flag) {
        List<Parse> items = getParses(type);
        List<Parse> filter = items.stream().filter(item -> item.getExt().getFlag().contains(flag)).toList();
        return filter.isEmpty() ? items : filter;
    }

    public List<String> getFlags() {
        return flags == null ? Collections.emptyList() : flags;
    }

    private void setFlags(List<String> flags) {
        this.flags = flags;
    }

    public List<String> getAds() {
        return ads == null ? Collections.emptyList() : ads;
    }

    private void setAds(List<String> ads) {
        this.ads = ads;
    }

    public List<String> getAllowedOrigins() {
        return allowedOrigins == null ? Collections.emptyList() : allowedOrigins;
    }

    private void setAllowedOrigins(List<String> allowedOrigins) {
        this.allowedOrigins = allowedOrigins;
    }

    public Parse getParse() {
        return parse == null ? new Parse() : parse;
    }

    public void setParse(Parse parse) {
        setParse(getConfig(), parse, true);
    }

    public Site getHome() {
        return home == null ? new Site() : home;
    }

    public void setHome(Site site) {
        setHome(getConfig(), site, true);
        RefreshEvent.home();
    }

    public String getWall() {
        return TextUtils.isEmpty(wall) ? "" : wall;
    }

    public Parse getParse(String name) {
        return getParses().stream().filter(item -> item.getName().equals(name)).findFirst().orElse(new Parse());
    }

    public Site getSite(String key) {
        return getSites().stream().filter(item -> item.getKey().equals(key)).findFirst().orElse(new Site());
    }

    private void setParse(Config config, Parse parse, boolean save) {
        this.parse = parse;
        this.parse.setSelected(true);
        config.setParse(parse.getName());
        getParses().forEach(item -> item.setSelected(parse));
        if (save) config.save();
    }

    private void setHome(Config config, Site site, boolean save) {
        home = site;
        home.setSelected(true);
        config.setHome(home.getKey());
        if (save) config.save();
        getSites().forEach(item -> item.setSelected(home));
    }

    private static class Loader {
        static volatile VodConfig INSTANCE = new VodConfig();
    }
}
