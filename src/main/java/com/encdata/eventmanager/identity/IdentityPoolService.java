package com.encdata.eventmanager.identity;

import com.encdata.eventmanager.EventManagerMod;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.regex.Matcher;
import java.util.Collections;
import java.util.Random;

public final class IdentityPoolService {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final OkHttpClient HTTP_CLIENT = new OkHttpClient();
    private static final Path IDENTITY_DIR = FabricLoader.getInstance().getConfigDir()
            .resolve("eventmanager")
            .resolve("identities");
    private static final Path NAME_PATH = IDENTITY_DIR.resolve("names.json");
    private static final Path SKIN_PATH = IDENTITY_DIR.resolve("skins.json");
    private static final Path REQUEST_PATH = IDENTITY_DIR.resolve("namemc_request.json");
    private static final Pattern VALID_NAME = Pattern.compile("^[A-Za-z0-9_]{3,16}$");
    private static final Pattern PROFILE_NAME_PATTERN = Pattern.compile("(?:https?://namemc\\.com)?/profile/([A-Za-z0-9_]{3,16})(?:[/?#\"'])");
    private static final int RANDOM_PAGE_SAMPLE_COUNT = 6;
    private static final int MAX_NAME_PAGE = 50;
    private static final int MAX_SKIN_PAGE = 50;

    private static final Object LOCK = new Object();
    private static final Set<String> knownNames = new LinkedHashSet<>();
    private static final Map<String, SkinDefinition> skinsByKey = new LinkedHashMap<>();
    private static final Random RANDOM = new Random();
    private static ScheduledExecutorService refreshExecutor;
    private static NameMcRequestConfig requestConfig;

    private IdentityPoolService() {
    }

    public static LoadResult load() {
        synchronized (LOCK) {
            boolean createdDefault = false;
            if (!Files.exists(NAME_PATH) || !Files.exists(SKIN_PATH)) {
                writeDefaultFiles();
                createdDefault = true;
            }

            NamePoolFile nameFile = readNameFile();
            SkinPoolFile skinFile = readSkinFile();
            requestConfig = readRequestConfig();
            knownNames.clear();
            skinsByKey.clear();

            int invalidNames = 0;
            if (nameFile.names != null) {
                for (String name : nameFile.names) {
                    if (!isValidName(name)) {
                        invalidNames++;
                        continue;
                    }
                    knownNames.add(name);
                }
            }

            int invalidSkins = 0;
            if (skinFile.skins != null) {
                for (SkinDefinition skin : skinFile.skins) {
                    if (skin == null || !skin.isValid()) {
                        invalidSkins++;
                        continue;
                    }
                    skinsByKey.putIfAbsent(buildSkinKey(skin), skin);
                }
            }

            if (createdDefault) {
                EventManagerMod.logInfo("Created default identity pools at {} and {}", NAME_PATH, SKIN_PATH);
            }
            if (requestConfig != null && requestConfig.hasOverrides()) {
                EventManagerMod.logInfo("Loaded NameMC request overrides from {}", REQUEST_PATH);
            }
            EventManagerMod.logInfo(
                    "Loaded {} names from {} ({} invalid skipped) and {} skins from {} ({} invalid skipped)",
                    knownNames.size(),
                    NAME_PATH,
                    invalidNames,
                    skinsByKey.size(),
                    SKIN_PATH,
                    invalidSkins
            );
            return new LoadResult(knownNames.size(), skinsByKey.size(), invalidNames, invalidSkins, createdDefault, IDENTITY_DIR);
        }
    }

    public static List<String> getNames() {
        synchronized (LOCK) {
            return new ArrayList<>(knownNames);
        }
    }

    public static List<SkinDefinition> getSkins() {
        synchronized (LOCK) {
            return new ArrayList<>(skinsByKey.values());
        }
    }

    public static List<IdentityDefinition> getIdentities() {
        synchronized (LOCK) {
            List<String> names = new ArrayList<>(knownNames);
            List<SkinDefinition> skins = new ArrayList<>(skinsByKey.values());
            List<IdentityDefinition> identities = new ArrayList<>();
            int count = Math.min(names.size(), skins.size());
            for (int i = 0; i < count; i++) {
                SkinDefinition skin = skins.get(i);
                identities.add(new IdentityDefinition(names.get(i), skin.skinTextureValue(), skin.skinSignature()));
            }
            return identities;
        }
    }

    public static Path getIdentityPath() {
        return IDENTITY_DIR;
    }

    public static ImportResult importFromUrl(String url) {
        List<String> candidates = expandSourceVariants(url);
        String lastError = "no request attempted";

        for (String candidate : candidates) {
            Request request = buildRequest(candidate);
            try (Response response = HTTP_CLIENT.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    lastError = "HTTP " + response.code();
                    continue;
                }

                ResponseBody body = response.body();
                if (body == null) {
                    lastError = "empty response body";
                    continue;
                }

                String bodyText = body.string();
                if (looksLikeJson(bodyText)) {
                    return importJsonBody(bodyText);
                }
                if (isNameMcUrl(candidate)) {
                    return importNameMc(candidate, bodyText);
                }

                lastError = "unsupported response format; expected JSON or a NameMC page";
            } catch (Exception e) {
                lastError = e.getMessage();
            }
        }

        EventManagerMod.logWarn("Failed to import identities from {} via variants {}", url, candidates);
        return new ImportResult(false, 0, 0, lastError, null);
    }

    public static void startBackgroundRefresh() {
        synchronized (LOCK) {
            if (refreshExecutor != null && !refreshExecutor.isShutdown()) {
                return;
            }

            ThreadFactory threadFactory = runnable -> {
                Thread thread = new Thread(runnable, "EventManager-IdentityRefresh");
                thread.setDaemon(true);
                return thread;
            };
            refreshExecutor = Executors.newSingleThreadScheduledExecutor(threadFactory);
            refreshExecutor.execute(IdentityPoolService::runBackgroundRefresh);
            refreshExecutor.scheduleWithFixedDelay(IdentityPoolService::runBackgroundRefresh, 300, 300, TimeUnit.SECONDS);
        }
    }

    public static void stopBackgroundRefresh() {
        synchronized (LOCK) {
            if (refreshExecutor != null) {
                refreshExecutor.shutdownNow();
                refreshExecutor = null;
            }
        }
    }

    private static ImportResult importJsonBody(String bodyText) {
        try {
            JsonObject json = JsonParser.parseString(bodyText).getAsJsonObject();
            List<String> importedNames = new ArrayList<>();
            List<SkinDefinition> importedSkins = new ArrayList<>();

            if (json.has("names")) {
                NamePoolFile nameFile = GSON.fromJson(json, NamePoolFile.class);
                if (nameFile.names != null) {
                    importedNames.addAll(nameFile.names);
                }
            }

            if (json.has("skins")) {
                SkinPoolFile skinFile = GSON.fromJson(json, SkinPoolFile.class);
                if (skinFile.skins != null) {
                    importedSkins.addAll(skinFile.skins);
                }
            }

            if (json.has("identities")) {
                IdentityPoolFile identityFile = GSON.fromJson(json, IdentityPoolFile.class);
                if (identityFile.identities != null) {
                    for (IdentityDefinition identity : identityFile.identities) {
                        if (identity == null) {
                            continue;
                        }
                        importedNames.add(identity.getName());
                        importedSkins.add(new SkinDefinition(identity.getSkinTextureValue(), identity.getSkinSignature()));
                    }
                }
            }

            MergeResult mergeResult = mergePools(importedNames, importedSkins);
            return new ImportResult(true, mergeResult.addedNames(), mergeResult.addedSkins(), null, mergeResult.loadResult());
        } catch (Exception e) {
            return new ImportResult(false, 0, 0, "invalid JSON: " + e.getMessage(), null);
        }
    }

    private static ImportResult importNameMc(String url, String bodyText) {
        Set<String> candidateNames = extractNameMcNames(url, bodyText);
        if (candidateNames.isEmpty()) {
            return new ImportResult(false, 0, 0, "no candidate profiles found on NameMC page", null);
        }

        int addedNames = 0;
        int addedSkins = 0;
        int resolved = 0;

        for (String name : candidateNames) {
            if (mergeName(name)) {
                addedNames++;
            }

            SkinDefinition skin = resolveSkinFromMinecraftName(name);
            if (skin == null) {
                continue;
            }

            resolved++;
            if (mergeSkin(skin)) {
                addedSkins++;
            }
        }

        LoadResult result = snapshotLoadResult();
        EventManagerMod.logInfo(
                "Imported NameMC candidates from {}: candidates={}, resolvedSkins={}, addedNames={}, addedSkins={}",
                url,
                candidateNames.size(),
                resolved,
                addedNames,
                addedSkins
        );
        if (addedNames == 0 && addedSkins == 0) {
            return new ImportResult(false, 0, 0, "no new names or skins were added", result);
        }
        return new ImportResult(true, addedNames, addedSkins, null, result);
    }

    private static void runBackgroundRefresh() {
        try {
            int addedNames = 0;
            int addedSkins = 0;
            List<String> sources = buildBackgroundSources();
            EventManagerMod.logInfo("Background identity refresh started");
            for (String source : sources) {
                ImportResult result = importFromUrl(source);
                if (result.success()) {
                    addedNames += result.importedNameCount();
                    addedSkins += result.importedSkinCount();
                } else {
                    EventManagerMod.logInfo("Background identity refresh source {} added nothing: {}", source, result.error());
                }
            }
            EventManagerMod.logInfo(
                    "Background identity refresh finished: addedNames={}, addedSkins={}, names={}, skins={}",
                    addedNames,
                    addedSkins,
                    snapshotLoadResult().loadedNameCount(),
                    snapshotLoadResult().loadedSkinCount()
            );
        } catch (Exception e) {
            EventManagerMod.logWarn("Background identity refresh failed", e);
        }
    }

    private static Set<String> extractNameMcNames(String url, String bodyText) {
        Document document = Jsoup.parse(bodyText, url);
        HttpUrl parsedUrl = HttpUrl.parse(url);
        Set<String> names = new LinkedHashSet<>();

        if (parsedUrl != null) {
            List<String> segments = parsedUrl.pathSegments();
            if (!segments.isEmpty() && ("profile".equals(segments.get(0)) || "skin".equals(segments.get(0)))) {
                String heading = document.select("h1").text().trim();
                if (isValidName(heading)) {
                    names.add(heading);
                    return names;
                }
            }
        }

        for (Element link : document.select("a[href^=/profile/]")) {
            String extracted = extractProfileNameFromHref(link.attr("href"));
            if (extracted != null) {
                names.add(extracted);
            }
            if (names.size() >= 32) {
                break;
            }
        }

        if (names.size() < 32) {
            for (Element link : document.select("a")) {
                String extracted = extractNameFromVisibleText(link.text());
                if (extracted != null) {
                    names.add(extracted);
                }
                if (names.size() >= 32) {
                    break;
                }
            }
        }

        if (names.isEmpty()) {
            Matcher matcher = PROFILE_NAME_PATTERN.matcher(bodyText);
            while (matcher.find() && names.size() < 32) {
                String candidate = matcher.group(1);
                if (isValidName(candidate)) {
                    names.add(candidate);
                }
            }
        }

        return names;
    }

    private static String extractProfileNameFromHref(String href) {
        if (href == null || !href.startsWith("/profile/")) {
            return null;
        }
        String tail = href.substring("/profile/".length());
        int slash = tail.indexOf('/');
        if (slash >= 0) {
            tail = tail.substring(0, slash);
        }
        int dot = tail.indexOf('.');
        if (dot >= 0) {
            tail = tail.substring(0, dot);
        }
        return isValidName(tail) ? tail : null;
    }

    private static String extractNameFromVisibleText(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        for (String token : text.trim().split("\\s+")) {
            if (isValidName(token)) {
                return token;
            }
        }
        return null;
    }

    private static SkinDefinition resolveSkinFromMinecraftName(String name) {
        try {
            JsonObject profile = fetchJson("https://api.mojang.com/users/profiles/minecraft/" + name);
            if (profile == null || !profile.has("id")) {
                return null;
            }

            String uuid = profile.get("id").getAsString();
            JsonObject session = fetchJson("https://sessionserver.mojang.com/session/minecraft/profile/" + uuid + "?unsigned=false");
            if (session == null || !session.has("properties")) {
                return null;
            }

            for (var propertyElement : session.getAsJsonArray("properties")) {
                JsonObject property = propertyElement.getAsJsonObject();
                if (!property.has("name") || !"textures".equals(property.get("name").getAsString())) {
                    continue;
                }
                if (!property.has("value") || !property.has("signature")) {
                    continue;
                }
                SkinDefinition skin = new SkinDefinition(
                        property.get("value").getAsString(),
                        property.get("signature").getAsString()
                );
                return skin.isValid() ? skin : null;
            }
        } catch (Exception e) {
            EventManagerMod.logWarn("Failed to resolve Mojang skin for {}", name, e);
        }
        return null;
    }

    private static JsonObject fetchJson(String url) throws IOException {
        Request request = buildRequest(url);
        try (Response response = HTTP_CLIENT.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                return null;
            }
            return JsonParser.parseString(response.body().string()).getAsJsonObject();
        }
    }

    private static Request buildRequest(String url) {
        NameMcRequestConfig config = requestConfig != null ? requestConfig : NameMcRequestConfig.template();
        Request.Builder builder = new Request.Builder()
                .url(url)
                .header("User-Agent", config.userAgent)
                .header("Accept", config.accept)
                .header("Accept-Language", config.acceptLanguage)
                .header("Referer", config.referer)
                .header("Cache-Control", "max-age=0")
                .header("Pragma", "no-cache")
                .header("Upgrade-Insecure-Requests", "1");
        if (config.cookie != null && !config.cookie.isBlank()) {
            builder.header("Cookie", config.cookie);
        }
        if (config.secChUa != null && !config.secChUa.isBlank()) {
            builder.header("sec-ch-ua", config.secChUa);
        }
        if (config.secChUaMobile != null && !config.secChUaMobile.isBlank()) {
            builder.header("sec-ch-ua-mobile", config.secChUaMobile);
        }
        if (config.secChUaPlatform != null && !config.secChUaPlatform.isBlank()) {
            builder.header("sec-ch-ua-platform", config.secChUaPlatform);
        }
        return builder.build();
    }

    private static boolean looksLikeJson(String bodyText) {
        String trimmed = bodyText.stripLeading();
        return trimmed.startsWith("{") || trimmed.startsWith("[");
    }

    private static boolean isNameMcUrl(String url) {
        HttpUrl parsed = HttpUrl.parse(url);
        return parsed != null && parsed.host() != null && parsed.host().contains("namemc.com");
    }

    private static List<String> expandSourceVariants(String url) {
        HttpUrl parsed = HttpUrl.parse(url);
        if (parsed == null || parsed.host() == null || !parsed.host().contains("namemc.com")) {
            return List.of(url);
        }

        List<String> variants = new ArrayList<>();
        String path = parsed.encodedPath();
        String query = parsed.encodedQuery();
        addVariant(variants, "https://namemc.com" + path + (query != null ? "?" + query : ""));
        addVariant(variants, "https://en.namemc.com" + path + (query != null ? "?" + query : ""));
        addVariant(variants, "https://www.namemc.com" + path + (query != null ? "?" + query : ""));
        return variants;
    }

    private static void addVariant(List<String> variants, String url) {
        if (!variants.contains(url)) {
            variants.add(url);
        }
    }

    private static NamePoolFile readNameFile() {
        try (Reader reader = Files.newBufferedReader(NAME_PATH)) {
            NamePoolFile file = GSON.fromJson(reader, NamePoolFile.class);
            return file != null ? file : new NamePoolFile();
        } catch (IOException e) {
            EventManagerMod.logError("Failed to read names from {}", NAME_PATH, e);
            return new NamePoolFile();
        }
    }

    private static SkinPoolFile readSkinFile() {
        try (Reader reader = Files.newBufferedReader(SKIN_PATH)) {
            SkinPoolFile file = GSON.fromJson(reader, SkinPoolFile.class);
            return file != null ? file : new SkinPoolFile();
        } catch (IOException e) {
            EventManagerMod.logError("Failed to read skins from {}", SKIN_PATH, e);
            return new SkinPoolFile();
        }
    }

    private static NameMcRequestConfig readRequestConfig() {
        if (!Files.exists(REQUEST_PATH)) {
            writeRequestFile(NameMcRequestConfig.template());
        }
        try (Reader reader = Files.newBufferedReader(REQUEST_PATH)) {
            NameMcRequestConfig config = GSON.fromJson(reader, NameMcRequestConfig.class);
            return config != null ? config : NameMcRequestConfig.template();
        } catch (IOException e) {
            EventManagerMod.logError("Failed to read NameMC request config from {}", REQUEST_PATH, e);
            return NameMcRequestConfig.template();
        }
    }

    private static void writeDefaultFiles() {
        writeNamesFile(new NamePoolFile());
        SkinPoolFile skins = new SkinPoolFile();
        skins.skins.add(new SkinDefinition("base64-texture-value-here", "texture-signature-here"));
        writeSkinsFile(skins);
        writeRequestFile(NameMcRequestConfig.template());
    }

    private static void writeNamesFile(NamePoolFile file) {
        try {
            Files.createDirectories(IDENTITY_DIR);
            try (Writer writer = Files.newBufferedWriter(NAME_PATH)) {
                GSON.toJson(file, writer);
            }
        } catch (IOException e) {
            EventManagerMod.logError("Failed to write names to {}", NAME_PATH, e);
        }
    }

    private static void writeSkinsFile(SkinPoolFile file) {
        try {
            Files.createDirectories(IDENTITY_DIR);
            try (Writer writer = Files.newBufferedWriter(SKIN_PATH)) {
                GSON.toJson(file, writer);
            }
        } catch (IOException e) {
            EventManagerMod.logError("Failed to write skins to {}", SKIN_PATH, e);
        }
    }

    private static void writeRequestFile(NameMcRequestConfig config) {
        try {
            Files.createDirectories(IDENTITY_DIR);
            try (Writer writer = Files.newBufferedWriter(REQUEST_PATH)) {
                GSON.toJson(config, writer);
            }
        } catch (IOException e) {
            EventManagerMod.logError("Failed to write NameMC request config to {}", REQUEST_PATH, e);
        }
    }

    private static boolean mergeName(String name) {
        synchronized (LOCK) {
            if (!isValidName(name) || knownNames.contains(name)) {
                return false;
            }
            knownNames.add(name);
            writeNamesFile(new NamePoolFile(knownNames));
            return true;
        }
    }

    private static boolean mergeSkin(SkinDefinition skin) {
        synchronized (LOCK) {
            if (skin == null || !skin.isValid()) {
                return false;
            }
            String key = buildSkinKey(skin);
            if (skinsByKey.containsKey(key)) {
                return false;
            }
            skinsByKey.put(key, skin);
            writeSkinsFile(new SkinPoolFile(skinsByKey.values()));
            return true;
        }
    }

    private static MergeResult mergePools(Iterable<String> names, Iterable<SkinDefinition> skins) {
        synchronized (LOCK) {
            int addedNames = 0;
            int addedSkins = 0;

            for (String name : names) {
                if (!isValidName(name) || knownNames.contains(name)) {
                    continue;
                }
                knownNames.add(name);
                addedNames++;
            }

            for (SkinDefinition skin : skins) {
                if (skin == null || !skin.isValid()) {
                    continue;
                }
                String key = buildSkinKey(skin);
                if (skinsByKey.containsKey(key)) {
                    continue;
                }
                skinsByKey.put(key, skin);
                addedSkins++;
            }

            writeNamesFile(new NamePoolFile(knownNames));
            writeSkinsFile(new SkinPoolFile(skinsByKey.values()));
            return new MergeResult(addedNames, addedSkins, snapshotLoadResult());
        }
    }

    private static boolean isValidName(String name) {
        return name != null && VALID_NAME.matcher(name).matches();
    }

    private static List<String> buildBackgroundSources() {
        List<String> sources = new ArrayList<>();
        for (int page : pickRandomPages(MAX_SKIN_PAGE, RANDOM_PAGE_SAMPLE_COUNT)) {
            sources.add("https://namemc.com/minecraft-skins/new?page=" + page);
        }
        for (int page : pickRandomPages(MAX_NAME_PAGE, RANDOM_PAGE_SAMPLE_COUNT)) {
            sources.add("https://namemc.com/minecraft-names?page=" + page);
        }
        return List.copyOf(sources);
    }

    private static List<Integer> pickRandomPages(int maxPage, int sampleCount) {
        List<Integer> pages = new ArrayList<>();
        for (int page = 1; page <= maxPage; page++) {
            pages.add(page);
        }
        Collections.shuffle(pages, RANDOM);
        return pages.subList(0, Math.min(sampleCount, pages.size()));
    }

    private static String buildSkinKey(SkinDefinition skin) {
        return skin.skinTextureValue() + ":" + skin.skinSignature();
    }

    private static LoadResult snapshotLoadResult() {
        return new LoadResult(knownNames.size(), skinsByKey.size(), 0, 0, false, IDENTITY_DIR);
    }

    private static class IdentityPoolFile {
        private List<IdentityDefinition> identities = new ArrayList<>();
    }

    private static class NamePoolFile {
        private List<String> names = new ArrayList<>();

        private NamePoolFile() {
        }

        private NamePoolFile(Iterable<String> names) {
            names.forEach(this.names::add);
        }
    }

    private static class SkinPoolFile {
        private List<SkinDefinition> skins = new ArrayList<>();

        private SkinPoolFile() {
        }

        private SkinPoolFile(Iterable<SkinDefinition> skins) {
            skins.forEach(this.skins::add);
        }
    }

    private static class NameMcRequestConfig {
        private String userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/146.0.0.0 Safari/537.36";
        private String accept = "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7";
        private String acceptLanguage = "ar-IQ,ar;q=0.9,en-IQ;q=0.8,en;q=0.7,en-US;q=0.6";
        private String referer = "";
        private String cookie = "";
        private String secChUa = "\"Chromium\";v=\"146\", \"Not-A.Brand\";v=\"24\", \"Google Chrome\";v=\"146\"";
        private String secChUaMobile = "?0";
        private String secChUaPlatform = "\"Windows\"";

        private boolean hasOverrides() {
            return cookie != null && !cookie.isBlank();
        }

        private static NameMcRequestConfig template() {
            return new NameMcRequestConfig();
        }
    }

    public record SkinDefinition(String skinTextureValue, String skinSignature) {
        public boolean isValid() {
            return skinTextureValue != null && !skinTextureValue.isBlank()
                    && skinSignature != null && !skinSignature.isBlank();
        }
    }

    public record LoadResult(
            int loadedNameCount,
            int loadedSkinCount,
            int invalidNameCount,
            int invalidSkinCount,
            boolean createdDefaultFile,
            Path path
    ) {
    }

    public record ImportResult(
            boolean success,
            int importedNameCount,
            int importedSkinCount,
            String error,
            LoadResult loadResult
    ) {
    }

    private record MergeResult(int addedNames, int addedSkins, LoadResult loadResult) {
    }
}
