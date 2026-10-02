package org.mtr.mod;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MinecraftVersionTest {
	@Test
	void selectsTheRequiredJavaVersion() {
		assertEquals(8, new MinecraftVersion("1.16.5").javaLanguageVersion());
		assertEquals(16, new MinecraftVersion("1.17.1").javaLanguageVersion());
		assertEquals(17, new MinecraftVersion("1.18.2").javaLanguageVersion());
		assertEquals(17, new MinecraftVersion("1.20.1").javaLanguageVersion());
		assertEquals(17, new MinecraftVersion("1.20.4").javaLanguageVersion());
		assertEquals(21, new MinecraftVersion("1.20.5").javaLanguageVersion());
		assertEquals(21, new MinecraftVersion("1.21.1").javaLanguageVersion());
		assertEquals(21, new MinecraftVersion("1.21.4").javaLanguageVersion());
	}

	@Test
	void selectsTheLootTableResourceDirectory() {
		assertEquals("loot_tables", new MinecraftVersion("1.20.4").lootTableDirectory());
		assertEquals("loot_tables", new MinecraftVersion("1.20.5").lootTableDirectory());
		assertEquals("loot_table", new MinecraftVersion("1.21.1").lootTableDirectory());
		assertEquals("loot_table", new MinecraftVersion("1.21.4").lootTableDirectory());
	}

	@Test
	void rejectsMalformedVersionsWithAnActionableMessage() {
		for (String version : new String[]{null, "", "1", "1.20.4-extra", "1.x.4", "2.20.4"}) {
			final IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> new MinecraftVersion(version));
			assertEquals("Expected a Minecraft release version such as 1.20.4, got: " + version, failure.getMessage());
		}
	}
}
