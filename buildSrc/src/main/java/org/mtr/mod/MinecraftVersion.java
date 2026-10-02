package org.mtr.mod;

public final class MinecraftVersion {
	public final int minor;
	private final int patch;

	public MinecraftVersion(String version) {
		if (version == null || !version.matches("1\\.\\d+\\.\\d+")) {
			throw new IllegalArgumentException("Expected a Minecraft release version such as 1.20.4, got: " + version);
		}
		final String[] components = version.split("\\.");
		minor = Integer.parseInt(components[1]);
		patch = Integer.parseInt(components[2]);
	}

	public int javaLanguageVersion() {
		if (minor <= 16) return 8;
		if (minor == 17) return 16;
		return minor > 20 || minor == 20 && patch >= 5 ? 21 : 17;
	}

	public String lootTableDirectory() {
		return minor >= 21 ? "loot_table" : "loot_tables";
	}
}
