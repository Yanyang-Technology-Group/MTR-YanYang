package org.mtr.render;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.authlib.GameProfile;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.client.resources.PlayerSkin;
import net.minecraft.resources.ResourceLocation;
import org.mtr.MTR;
import org.mtr.core.tool.Utilities;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Manages the skin pool used by AI passengers.
 *
 * <p>Two tiers of skins are provided:</p>
 * <ul>
 *   <li><b>Offline default pool</b> — the 9 vanilla default skins, deterministically assigned
 *       per passenger id. Always available, no network access.</li>
 *   <li><b>Online skins (optional)</b> — real player skins fetched asynchronously from either
 *       Mojang (genuine accounts) or a CustomSkinLoader-compatible skin site (for example
 *       littleskin.cn), configured via {@code config/mtr_passenger_skins.json}. Downloaded
 *       skins are cached on disk and registered onto the render thread.</li>
 * </ul>
 *
 * <p>The pool is published as a single volatile array; {@link #getSkin(long)} is lock-free and
 * safe to call from the render thread.</p>
 */
public final class PassengerSkinManager {

	private static final int MAX_ONLINE_NAMES = 64;
	private static final int HTTP_TIMEOUT_SECONDS = 10;
	private static final long ONLINE_RETRY_INTERVAL = 10 * Utilities.MILLIS_PER_MINUTE;

	private static final long GOLDEN_RATIO_FRACTION = 0x9E3779B97F4A7C15L;

	private static final Path configPath;
	private static final Path cacheDir;

	private static volatile PlayerSkin[] skinPool;
	private static volatile boolean onlineSkinsEnabled;

	private static final AtomicBoolean loadedDefaults = new AtomicBoolean(false);
	private static final AtomicBoolean fetchStarted = new AtomicBoolean(false);
	private static final Map<String, Long> failedNames = new HashMap<>();

	// Configuration, reloaded on init
	private static volatile List<String> configuredNames = List.of();
	private static volatile String source = "mojang";
	private static volatile String cslUrl = "https://littleskin.cn";
	private static volatile long refreshMillis = 12L * Utilities.MILLIS_PER_HOUR;

	static {
		final Path gameDir = Minecraft.getInstance().gameDirectory.toPath();
		configPath = gameDir.resolve("config/mtr_passenger_skins.json");
		cacheDir = gameDir.resolve("cache/mtr/passenger-skins");
	}

	private PassengerSkinManager() {
	}

	/**
	 * Loads the configuration and (lazily) the skin pool. Called from the client init.
	 */
	public static void init() {
		readConfig();
	}

	/**
	 * @return the skin for a passenger, deterministically assigned from the current pool
	 */
	public static PlayerSkin getSkin(long passengerId) {
		ensureDefaultsLoaded();
		final PlayerSkin[] pool = skinPool;
		return pool[(int) Math.floorMod(passengerId * GOLDEN_RATIO_FRACTION, pool.length)];
	}

	/**
	 * Re-reads the configuration file and restarts the asynchronous online skin fetch.
	 */
	public static void reload() {
		readConfig();
		fetchStarted.set(false);
		ensureDefaultsLoaded();
	}

	private static void ensureDefaultsLoaded() {
		if (loadedDefaults.compareAndSet(false, true)) {
			readConfig();
			skinPool = buildDefaultPool();
		}
		if (onlineSkinsEnabled && fetchStarted.compareAndSet(false, true)) {
			final Thread thread = new Thread(PassengerSkinManager::fetchOnlineSkins, "MTR Passenger Skin Loader");
			thread.setDaemon(true);
			thread.start();
		}
	}

	private static void readConfig() {
		try {
			if (Files.exists(configPath)) {
				final JsonObject config = JsonParser.parseString(Files.readString(configPath, StandardCharsets.UTF_8)).getAsJsonObject();
				onlineSkinsEnabled = config.has("onlineSkins") && config.get("onlineSkins").getAsBoolean();
				if (config.has("source")) {
					source = config.get("source").getAsString();
				}
				if (config.has("cslUrl")) {
					cslUrl = config.get("cslUrl").getAsString();
				}
				final List<String> names = new ArrayList<>();
				if (config.has("names")) {
					for (final JsonElement element : config.getAsJsonArray("names")) {
						final String name = element.getAsString().trim();
						if (!name.isEmpty() && !names.contains(name) && names.size() < MAX_ONLINE_NAMES) {
							names.add(name);
						}
					}
				}
				configuredNames = List.copyOf(names);
				if (config.has("refreshMinutes")) {
					refreshMillis = Math.max(10, config.get("refreshMinutes").getAsLong()) * Utilities.MILLIS_PER_MINUTE;
				}
			}
		} catch (Exception e) {
			MTR.LOGGER.warn("Failed to read passenger skin config, using defaults", e);
		}
	}

	/**
	 * Builds the 9-skin vanilla default pool by picking distinct {@link DefaultPlayerSkin}
	 * entries via deterministic offline UUIDs.
	 */
	private static PlayerSkin[] buildDefaultPool() {
		final List<PlayerSkin> skins = new ArrayList<>();
		for (int i = 0; i < 64 && skins.size() < 9; i++) {
			final UUID uuid = UUID.nameUUIDFromBytes(("mtr_passenger_" + i).getBytes(StandardCharsets.UTF_8));
			final PlayerSkin skin = DefaultPlayerSkin.get(uuid);
			if (!skins.contains(skin)) {
				skins.add(skin);
			}
		}
		if (skins.isEmpty()) {
			skins.add(DefaultPlayerSkin.get(new UUID(0, 0)));
		}
		return skins.toArray(new PlayerSkin[0]);
	}

	private static void fetchOnlineSkins() {
		if (configuredNames.isEmpty()) {
			return;
		}

		try {
			Files.createDirectories(cacheDir);
		} catch (Exception e) {
			MTR.LOGGER.warn("Failed to create passenger skin cache directory", e);
			return;
		}

		final List<PlayerSkin> onlineSkins = new ArrayList<>();
		for (final String name : configuredNames) {
			final PlayerSkin skin = loadSkin(name);
			if (skin != null) {
				onlineSkins.add(skin);
			}
		}

		if (!onlineSkins.isEmpty()) {
			// Blend online skins with the vanilla default pool, keeping the defaults for variety.
			final PlayerSkin[] defaults = buildDefaultPool();
			final PlayerSkin[] combined = new PlayerSkin[onlineSkins.size() + defaults.length];
			for (int i = 0; i < onlineSkins.size(); i++) {
				combined[i] = onlineSkins.get(i);
			}
			System.arraycopy(defaults, 0, combined, onlineSkins.size(), defaults.length);

			// Texture registration must happen on the render thread before the pool is published.
			Minecraft.getInstance().execute(() -> {
				for (final PlayerSkin skin : onlineSkins) {
					// Already registered when loaded (see loadFromCache / downloadAndRegister).
				}
				skinPool = combined;
				MTR.LOGGER.info("Loaded {} online passenger skins", onlineSkins.size());
			});
		}
	}

	/**
	 * Loads one skin by player name: disk cache first (unless stale), then the configured
	 * source (Mojang or CSL skin site).
	 */
	private static PlayerSkin loadSkin(String name) {
		final long now = System.currentTimeMillis();
		final Long lastFailure = failedNames.get(name);
		if (lastFailure != null && now - lastFailure < ONLINE_RETRY_INTERVAL) {
			return null;
		}

		for (final PlayerSkin.Model model : PlayerSkin.Model.values()) {
			final Path cachedFile = cacheDir.resolve(safeFileName(name) + "." + model.id() + ".png");
			if (Files.exists(cachedFile)) {
				try {
					final boolean stale = now - Files.getLastModifiedTime(cachedFile).toMillis() > refreshMillis;
					if (!stale) {
						final PlayerSkin skin = registerSkin(name, Files.readAllBytes(cachedFile), model);
						if (skin != null) {
							return skin;
						}
					}
				} catch (Exception e) {
					MTR.LOGGER.warn("Failed to load cached passenger skin for {}", name, e);
				}
			}
		}

		try {
			if ("csl".equalsIgnoreCase(source)) {
				return fetchFromCsl(name);
			}
			return fetchFromMojang(name);
		} catch (Exception e) {
			MTR.LOGGER.warn("Failed to fetch passenger skin for {} (source {})", name, source, e);
			failedNames.put(name, now);
			return null;
		}
	}

	/**
	 * Mojang flow: name -> UUID -> profile with signed textures -> skin URL.
	 */
	private static PlayerSkin fetchFromMojang(String name) throws Exception {
		final String uuid = httpGetJson("https://api.mojang.com/users/profiles/minecraft/" + urlEncode(name));
		if (uuid == null) {
			return null;
		}
		final JsonObject profileResponse = JsonParser.parseString(uuid).getAsJsonObject();
		final String id = profileResponse.get("id").getAsString();

		// Be polite to the Mojang API.
		Thread.sleep(600);
		final String profile = httpGetJson("https://sessionserver.mojang.com/session/minecraft/profile/" + id + "?unsigned=false");
		if (profile == null) {
			return null;
		}
		final JsonObject profileJson = JsonParser.parseString(profile).getAsJsonObject();
		for (final JsonElement property : profileJson.getAsJsonArray("properties")) {
			final JsonObject propertyObject = property.getAsJsonObject();
			if ("textures".equals(propertyObject.get("name").getAsString())) {
				final JsonObject textures = JsonParser.parseString(new String(Base64.getDecoder().decode(propertyObject.get("value").getAsString()), StandardCharsets.UTF_8)).getAsJsonObject().getAsJsonObject("textures");
				final JsonObject skin = textures.getAsJsonObject("SKIN");
				if (skin != null) {
					final PlayerSkin.Model model = skin.has("metadata") && skin.getAsJsonObject("metadata").has("model") && "slim".equals(skin.getAsJsonObject("metadata").get("model").getAsString()) ? PlayerSkin.Model.SLIM : PlayerSkin.Model.WIDE;
					return downloadAndRegister(name, skin.get("url").getAsString(), model);
				}
			}
		}
		return null;
	}

	/**
	 * CustomSkinLoader API flow: {@code <site>/csl/<name>} -> skin URLs.
	 */
	private static PlayerSkin fetchFromCsl(String name) throws Exception {
		final String response = httpGetJson(cslUrl.replaceAll("/$", "") + "/csl/" + urlEncode(name));
		if (response == null) {
			return null;
		}
		final JsonObject json = JsonParser.parseString(response).getAsJsonObject();
		final JsonObject skins = json.has("skins") ? json.getAsJsonObject("skins") : null;
		if (skins == null) {
			return null;
		}
		// Prefer the slim model when available for extra variety.
		if (skins.has("slim")) {
			return downloadAndRegister(name, skins.get("slim").getAsString(), PlayerSkin.Model.SLIM);
		}
		if (skins.has("default")) {
			return downloadAndRegister(name, skins.get("default").getAsString(), PlayerSkin.Model.WIDE);
		}
		return null;
	}

	private static PlayerSkin downloadAndRegister(String name, String url, PlayerSkin.Model model) throws Exception {
		final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(HTTP_TIMEOUT_SECONDS)).followRedirects(HttpClient.Redirect.NORMAL).build();
		final HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(HTTP_TIMEOUT_SECONDS)).header("User-Agent", "MTR-YanYang").GET().build();
		final HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
		if (response.statusCode() != 200) {
			throw new IllegalStateException("Skin download returned " + response.statusCode());
		}
		final byte[] bytes = response.body();

		// Persist to disk for future sessions.
		try {
			Files.write(cacheDir.resolve(safeFileName(name) + "." + model.id() + ".png"), bytes);
		} catch (Exception e) {
			MTR.LOGGER.warn("Failed to cache passenger skin for {}", name, e);
		}

		return registerSkin(name, bytes, model);
	}

	/**
	 * Validates and registers the skin texture. Texture upload must run on the render thread;
	 * NativeImage parsing is done on the calling (worker) thread.
	 */
	private static PlayerSkin registerSkin(String name, byte[] bytes, PlayerSkin.Model model) throws Exception {
		final NativeImage image;
		try (final InputStream inputStream = new ByteArrayInputStream(bytes)) {
			image = NativeImage.read(inputStream);
		}
		if (image.getWidth() != 64 || image.getHeight() != 64) {
			// Legacy 64x32 skins are not supported; reject anything that is not modern-format.
			MTR.LOGGER.warn("Passenger skin for {} is {}x{}, expected 64x64; skipping", name, image.getWidth(), image.getHeight());
			image.close();
			return null;
		}

		final ResourceLocation textureIdentifier = ResourceLocation.fromNamespaceAndPath("mtr", "passenger_skin/" + safeFileName(name) + "_" + Integer.toHexString(model.id().hashCode()));
		final GameProfile profile = new GameProfile(UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8)), name);
		Minecraft.getInstance().execute(() -> {
			try {
				Minecraft.getInstance().getTextureManager().register(textureIdentifier, new DynamicTexture(image));
			} catch (Exception e) {
				MTR.LOGGER.warn("Failed to register passenger skin texture for {}", name, e);
			}
		});
		return new PlayerSkin(textureIdentifier, null, null, null, model, false);
	}

	private static String httpGetJson(String url) throws Exception {
		final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(HTTP_TIMEOUT_SECONDS)).followRedirects(HttpClient.Redirect.NORMAL).build();
		final HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(HTTP_TIMEOUT_SECONDS)).header("User-Agent", "MTR-YanYang").header("Accept", "application/json").GET().build();
		final HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
		if (response.statusCode() == 204 || response.statusCode() == 404) {
			return null;
		}
		if (response.statusCode() != 200) {
			throw new IllegalStateException("GET " + url + " returned " + response.statusCode());
		}
		return response.body();
	}

	private static String urlEncode(String value) {
		return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8);
	}

	private static String safeFileName(String name) {
		return name.replaceAll("[^a-zA-Z0-9_-]", "_").toLowerCase(Locale.ROOT);
	}
}
