/*
 * SPDX-License-Identifier: MIT
 *
 * GenerateGolden.java — produce byte-exact reference buffers from the real
 * Minecraft Transit Railway rendering code.
 *
 * The native rewrite in native/ claims two things at once: that it is faster, and
 * that it produces the same GPU buffers. The second claim is only worth anything
 * if the reference comes from the actual pipeline rather than from a reading of
 * it. This program is that reference.
 *
 * It links against the same prebuilt jars the mod compiles against and runs the
 * real classes:
 *
 *   de.javagl.obj.ObjReader / MtlReader / ObjSplitting / ObjUtils
 *   org.mtr.mapping.render.model.RawMesh / RawModel / Face
 *   org.mtr.mapping.render.vertex.Vertex
 *   org.mtr.mapping.render.batch.MaterialProperties
 *   org.mtr.mapping.mapper.OptimizedModel.ShaderType
 *   org.joml.Vector3f
 *
 * Every geometric transformation, every de-duplication decision and the whole
 * vertex layout therefore come from the shipping code. Only two thin pieces are
 * re-implemented here, both because they need a running Minecraft client:
 *
 *   1. ObjModel.ObjModel's constructor and its bounds accumulation. Its signature
 *      takes org.mtr.mapping.holder.Identifier, which needs a live resource
 *      manager.
 *   2. MaterialProperties' texture field, which is passed null. The texture only
 *      reaches the RenderLayer, never the geometry, and MaterialProperties equals()
 *      is the merge key - so texture identity is tracked alongside instead, as the
 *      resolved string the mod would have turned into an Identifier.
 *
 * Output format: see native/tests/golden_format.hpp. Two files are written per
 * case: the binary golden the native tests consume, and a small `.case` manifest
 * telling the native harness how to rebuild it.
 *
 * Usage:
 *   java -cp <jars> GenerateGolden <manifest.txt>
 *
 * Manifest lines:
 *   case <name>
 *   obj <path relative to the manifest directory>
 *   mtl <path relative to the manifest directory>
 *   flipTextureV <0|1>
 *   materialGroupTextures <0|1>
 *   schema <path relative to the manifest directory>   # VehicleResource-style scale
 *   transform <shaderType>,<x>,<y>,<z>,<flipped>
 *   end
 *
 * `transform` may repeat; each line is one ObjModel.addTransformation call.
 */

import de.javagl.obj.FloatTuple;
import de.javagl.obj.Mtl;
import de.javagl.obj.MtlReader;
import de.javagl.obj.Obj;
import de.javagl.obj.ObjFace;
import de.javagl.obj.ObjReader;
import de.javagl.obj.ObjSplitting;
import de.javagl.obj.ObjUtils;
import org.joml.Vector3f;
import org.mtr.mapping.holder.Identifier;
import org.mtr.mapping.mapper.OptimizedModel;
import org.mtr.mapping.render.batch.MaterialProperties;
import org.mtr.mapping.render.model.Face;
import org.mtr.mapping.render.vertex.Vertex;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

public final class GenerateGolden {

	private static final int MAGIC_VERSION = 3;

	// -----------------------------------------------------------------------
	// Material identity
	// -----------------------------------------------------------------------

	/**
	 * The merge key that RawModel uses, plus the resolved texture string.
	 *
	 * MaterialProperties itself compares shader type, texture and vertex attribute
	 * state, and the texture is the only one of those that varies along the OBJ
	 * path. Because the texture has to be null here, this wrapper carries the
	 * resolved string and uses it in place of the Identifier.
	 */
	private static final class MaterialKey {

		final MaterialProperties properties;
		final String texture;

		MaterialKey(MaterialProperties properties, String texture) {
			this.properties = properties;
			this.texture = texture;
		}

		@Override
		public boolean equals(Object object) {
			if (this == object) {
				return true;
			}
			if (!(object instanceof MaterialKey)) {
				return false;
			}
			final MaterialKey other = (MaterialKey) object;
			return properties.equals(other.properties) && Objects.equals(texture, other.texture);
		}

		@Override
		public int hashCode() {
			return Objects.hash(properties, texture);
		}
	}

	// -----------------------------------------------------------------------
	// Thin wrappers over the real geometry classes
	// -----------------------------------------------------------------------

	/** A real RawMesh plus the material identity RawModel would merge on. */
	private static final class Mesh {

		final MaterialKey material;
		final org.mtr.mapping.render.model.RawMesh raw;

		Mesh(MaterialKey material, org.mtr.mapping.render.model.RawMesh raw) {
			this.material = material;
			this.raw = raw;
		}

		/**
		 * RawMesh's copy constructor semantics: a CUTOUT source adopts the requested
		 * shader type, every other source keeps its own MaterialProperties. The native
		 * implementation relies on the identical rule.
		 */
		Mesh copyWithShader(OptimizedModel.ShaderType requested) {
			final MaterialKey newMaterial;
			if (raw.materialProperties.shaderType == OptimizedModel.ShaderType.CUTOUT) {
				newMaterial = new MaterialKey(
						new MaterialProperties(requested, null,
								raw.materialProperties.vertexAttributeState.color),
						material.texture);
			} else {
				newMaterial = material;
			}
			final org.mtr.mapping.render.model.RawMesh copy =
					new org.mtr.mapping.render.model.RawMesh(raw.materialProperties);
			raw.vertices.forEach(vertex -> copy.vertices.add(new Vertex(vertex)));
			raw.faces.forEach(face -> copy.faces.add(new Face(face)));
			return new Mesh(newMaterial, copy);
		}

		Mesh copy() {
			final org.mtr.mapping.render.model.RawMesh copy =
					new org.mtr.mapping.render.model.RawMesh(raw.materialProperties);
			raw.vertices.forEach(vertex -> copy.vertices.add(new Vertex(vertex)));
			raw.faces.forEach(face -> copy.faces.add(new Face(face)));
			return new Mesh(material, copy);
		}
	}

	/** Mirrors RawModel's merge-by-material behaviour. */
	private static final class Model {

		final Map<MaterialKey, org.mtr.mapping.render.model.RawMesh> byMaterial = new LinkedHashMap<>();

		void append(Mesh mesh) {
			org.mtr.mapping.render.model.RawMesh target = byMaterial.get(mesh.material);
			if (target == null) {
				target = new org.mtr.mapping.render.model.RawMesh(mesh.raw.materialProperties);
				byMaterial.put(mesh.material, target);
			}
			target.append(mesh.raw);
		}

		/** generateNormals then distinct, once per merged material, like fromObjModels. */
		List<Mesh> finish() {
			final List<Mesh> result = new ArrayList<>();
			for (final Map.Entry<MaterialKey, org.mtr.mapping.render.model.RawMesh> entry
					: byMaterial.entrySet()) {
				final org.mtr.mapping.render.model.RawMesh merged = entry.getValue();
				merged.generateNormals();
				merged.distinct();
				result.add(new Mesh(entry.getKey(), merged));
			}
			return result;
		}
	}

	// -----------------------------------------------------------------------
	// Material resolution — mirrors ObjModelLoader and CustomResourceTools
	// -----------------------------------------------------------------------

	/** Verbatim copy of ObjModelLoader.legacyMapping. */
	private static OptimizedModel.ShaderType legacyMapping(String type) {
		switch (type.toLowerCase(Locale.ENGLISH)) {
			case "exteriortranslucent":
				return OptimizedModel.ShaderType.TRANSLUCENT;
			case "light":
				return OptimizedModel.ShaderType.CUTOUT_GLOWING;
			case "always_on_light":
			case "lighttranslucent":
				return OptimizedModel.ShaderType.TRANSLUCENT_GLOWING;
			case "interior":
				return OptimizedModel.ShaderType.CUTOUT_BRIGHT;
			case "interior_translucent":
			case "interiortranslucent":
				return OptimizedModel.ShaderType.TRANSLUCENT_BRIGHT;
			default:
				return OptimizedModel.ShaderType.CUTOUT;
		}
	}

	/** Verbatim copy of ObjModelLoader.splitMaterialOptions. */
	private static Map<String, String> splitMaterialOptions(String source) {
		final Map<String, String> result = new HashMap<>();
		final String[] majorParts = source.split("#", 2);
		result.put("", majorParts[0]);
		if (majorParts.length > 1) {
			for (final String minorPart : majorParts[1].split(",")) {
				final String[] tokens = minorPart.split("=", 2);
				if (tokens.length > 1) {
					result.put(tokens[0], tokens[1]);
				} else if (!result.containsKey("#")) {
					result.put("#", tokens[0]);
				} else {
					result.put(tokens[0].toLowerCase(Locale.ROOT), "1");
				}
			}
		}
		return result;
	}

	/** Verbatim copy of ObjModelLoader.mergeColor. */
	private static int mergeColor(int r, int g, int b, int a) {
		return r << 24 | g << 16 | b << 8 | a;
	}

	/** Mirrors CustomResourceTools.formatIdentifierString. */
	private static String formatIdentifierString(String text) {
		final StringBuilder builder = new StringBuilder();
		boolean first = true;
		for (final String part : text.toLowerCase(Locale.ENGLISH).split(":")) {
			if (!first) {
				builder.append(':');
			}
			first = false;
			builder.append(part.replaceAll("[^a-z0-9/._-]", "_"));
		}
		return builder.toString();
	}

	/** Mirrors CustomResourceTools.formatIdentifier. */
	private static String formatIdentifier(String identifierString, String extension) {
		final String formatted = formatIdentifierString(identifierString);
		if (formatted.isEmpty()) {
			return null;
		}
		return formatted.split("\\.")[0] + "." + extension;
	}

	private static final String MOD_ID = "mtr";
	private static final String WHITE_TEXTURE = MOD_ID + ":textures/block/white.png";
	private static final String TRANSPARENT_TEXTURE = MOD_ID + ":textures/block/transparent.png";

	/** Mirrors CustomResourceTools.getResourceFromSamePath. */
	private static String getResourceFromSamePath(String basePath, String resource, String extension) {
		if (resource.contains(":")) {
			final String formatted = formatIdentifier(resource, extension);
			return formatted == null ? TRANSPARENT_TEXTURE : formatted;
		}
		final String[] split = basePath.split("/");
		split[split.length - 1] = resource;
		final String formatted = formatIdentifier(String.join("/", split), extension);
		return formatted == null ? TRANSPARENT_TEXTURE : formatted;
	}

	// -----------------------------------------------------------------------
	// Case model
	// -----------------------------------------------------------------------

	private static final class Transform {

		final int shaderType;
		final double x;
		final double y;
		final double z;
		final boolean flipped;

		Transform(int shaderType, double x, double y, double z, boolean flipped) {
			this.shaderType = shaderType;
			this.x = x;
			this.y = y;
			this.z = z;
			this.flipped = flipped;
		}

		String toCaseLine() {
			return shaderType + "," + x + "," + y + "," + z + "," + (flipped ? 1 : 0);
		}
	}

	private static final class Case {

		String name = "";
		Path objPath;
		Path mtlPath;
		boolean flipTextureV;
		boolean materialGroupTextures = true;
		/** Model-resource level transform, applied to the ObjModel before the vehicle level one. */
		double[] modelTranslation = {0.0, 0.0, 0.0};
		double[] modelRotation = {0.0, 0.0, 0.0};
		double[] modelScale = {1.0, 1.0, 1.0};
		boolean[] modelMirror = {false, false, false};
		/** Vehicle-level transform from StoredModelResourceBase.transform. */
		double[] schemaTranslation = {0.0, 0.0, 0.0};
		double[] schemaRotation = {0.0, 0.0, 0.0};
		double[] schemaScale = {1.0, 1.0, 1.0};
		boolean[] schemaMirror = {false, false, false};
		final List<Transform> transforms = new ArrayList<>();
	}

	private static final class CaseRecord {

		final String key;
		final OptimizedModel.ShaderType shaderType;
		final boolean flipTextureV;
		final String texture;
		final int color;

		CaseRecord(String key, OptimizedModel.ShaderType shaderType, boolean flipTextureV,
				String texture, int color) {
			this.key = key;
			this.shaderType = shaderType;
			this.flipTextureV = flipTextureV;
			this.texture = texture;
			this.color = color;
		}
	}

	// -----------------------------------------------------------------------
	// Main
	// -----------------------------------------------------------------------

	public static void main(String[] args) throws Exception {
		if (args.length < 1) {
			System.err.println("usage: GenerateGolden <manifest.txt>");
			System.exit(2);
		}

		final Path manifest = Paths.get(args[0]).toAbsolutePath();
		final Path directory = manifest.getParent();
		final List<Case> cases = readManifest(manifest);
		System.out.println("manifest: " + manifest);
		System.out.println("cases:    " + cases.size());
		System.out.println();

		int failures = 0;
		for (final Case testCase : cases) {
			try {
				build(testCase, directory);
			} catch (Throwable throwable) {
				System.out.println("  FAIL " + testCase.name + ": " + throwable);
				throwable.printStackTrace(System.out);
				failures++;
			}
		}

		System.out.println();
		System.out.println(failures == 0
				? "all " + cases.size() + " case(s) generated"
				: failures + " case(s) failed");
		System.exit(failures == 0 ? 0 : 1);
	}

	private static List<Case> readManifest(Path manifest) throws IOException {
		final List<Case> cases = new ArrayList<>();
		Case current = null;

		for (final String rawLine : Files.readAllLines(manifest, StandardCharsets.UTF_8)) {
			final String line = rawLine.trim();
			if (line.isEmpty() || line.startsWith("#")) {
				continue;
			}
			final int space = line.indexOf(' ');
			final String keyword = space < 0 ? line : line.substring(0, space);
			final String value = space < 0 ? "" : line.substring(space + 1).trim();

			if ("case".equals(keyword)) {
				current = new Case();
				current.name = value;
				cases.add(current);
				continue;
			}
			if ("end".equals(keyword)) {
				current = null;
				continue;
			}
			if (current == null) {
				throw new IOException("statement outside a case block: " + line);
			}

			switch (keyword) {
				case "obj":
					current.objPath = Paths.get(value);
					break;
				case "mtl":
					current.mtlPath = value.isEmpty() ? null : Paths.get(value);
					break;
				case "flipTextureV":
					current.flipTextureV = "1".equals(value);
					break;
				case "materialGroupTextures":
					current.materialGroupTextures = !"0".equals(value);
					break;
				case "modelTranslation":
					current.modelTranslation = parseDoubles(value, 3);
					break;
				case "modelRotation":
					current.modelRotation = parseDoubles(value, 3);
					break;
				case "modelScale":
					current.modelScale = parseDoubles(value, 3);
					break;
				case "modelMirror": {
					final double[] values = parseDoubles(value, 3);
					current.modelMirror = new boolean[]{
							values[0] != 0.0, values[1] != 0.0, values[2] != 0.0};
					break;
				}
				case "schemaTranslation":
					current.schemaTranslation = parseDoubles(value, 3);
					break;
				case "schemaRotation":
					current.schemaRotation = parseDoubles(value, 3);
					break;
				case "schemaScale":
					current.schemaScale = parseDoubles(value, 3);
					break;
				case "schemaMirror": {
					final double[] values = parseDoubles(value, 3);
					current.schemaMirror = new boolean[]{
							values[0] != 0.0, values[1] != 0.0, values[2] != 0.0};
					break;
				}
				case "transform": {
					final String[] parts = value.split(",");
					if (parts.length != 5) {
						throw new IOException("transform needs 5 fields: " + line);
					}
					current.transforms.add(new Transform(
							Integer.parseInt(parts[0].trim()),
							Double.parseDouble(parts[1].trim()),
							Double.parseDouble(parts[2].trim()),
							Double.parseDouble(parts[3].trim()),
							"1".equals(parts[4].trim())));
					break;
				}
				default:
					throw new IOException("unknown keyword: " + keyword);
			}
		}

		return cases;
	}

	private static double[] parseDoubles(String value, int count) throws IOException {
		final String[] parts = value.split(",");
		if (parts.length != count) {
			throw new IOException("expected " + count + " comma separated numbers, got: " + value);
		}
		final double[] result = new double[count];
		for (int i = 0; i < count; i++) {
			result[i] = Double.parseDouble(parts[i].trim());
		}
		return result;
	}

	// -----------------------------------------------------------------------
	// One case
	// -----------------------------------------------------------------------

	/** Result of loading one OBJ group, mirroring ObjModelLoader.loadModel. */
	private static final class GroupResult {

		final Map<String, CaseRecord> records = new LinkedHashMap<>();
		final List<Mesh> meshes = new ArrayList<>();
		final float[] bounds = {
				Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE,
				-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE
		};
	}

	private static void build(Case testCase, Path directory) throws IOException {
		final Path objFile = directory.resolve(testCase.objPath);
		final Path mtlFile = testCase.mtlPath == null ? null : directory.resolve(testCase.mtlPath);

		final String objText = new String(Files.readAllBytes(objFile), StandardCharsets.UTF_8);
		final String mtlText = mtlFile == null ? null
				: new String(Files.readAllBytes(mtlFile), StandardCharsets.UTF_8);
		final String modelResource = objFile.getFileName().toString();

		// --- ObjReader / MtlReader, exactly as ObjModelLoader calls them -------
		final Obj sourceObj = ObjReader.read(
				new ByteArrayInputStream(objText.getBytes(StandardCharsets.UTF_8)));
		final Map<String, Mtl> materials = new HashMap<>();
		if (mtlText != null && !mtlText.isEmpty()) {
			try {
				MtlReader.read(new ByteArrayInputStream(mtlText.getBytes(StandardCharsets.UTF_8)))
						.forEach(mtl -> materials.put(mtl.getName(), mtl));
			} catch (Exception exception) {
				System.out.println("  note: MTL failed to parse, continuing without materials: "
						+ exception);
				materials.clear();
			}
		}

		final Function<String, String> textureResolver = textureString -> {
			if (textureString == null || textureString.isEmpty()) {
				return WHITE_TEXTURE;
			}
			if (textureString.equals("default.png")) {
				// The vehicle's own texture. Pinned to a stable name so the golden is
				// reproducible without the pack's PNG.
				return MOD_ID + ":textures/vehicle/default.png";
			}
			return getResourceFromSamePath(modelResource, textureString, "png");
		};

		// --- ObjSplitting.splitByGroups -> ObjModelLoader.loadModel -----------
		final List<GroupResult> groups = new ArrayList<>();
		ObjSplitting.splitByGroups(sourceObj).forEach((groupName, groupObj) ->
				groups.add(loadGroup(groupObj, materials, textureResolver,
						testCase.materialGroupTextures, testCase.flipTextureV)));

		// --- ObjModel.ObjModel.loadModel: rotate X 180, then measure bounds ----
		// The rotation is a RawMesh operation and the bounds are accumulated after
		// it, in that order, in the reference implementation.
		for (final GroupResult group : groups) {
			for (final Mesh mesh : group.meshes) {
				mesh.raw.applyRotation(new org.mtr.mapping.holder.Vector3f(1.0F, 0.0F, 0.0F), 180.0F);
			}
			for (final Mesh mesh : group.meshes) {
				for (final Vertex vertex : mesh.raw.vertices) {
					final float x = vertex.position.getX();
					final float y = vertex.position.getY();
					final float z = vertex.position.getZ();
					group.bounds[0] = Math.min(group.bounds[0], x);
					group.bounds[1] = Math.min(group.bounds[1], y);
					group.bounds[2] = Math.min(group.bounds[2], z);
					group.bounds[3] = Math.max(group.bounds[3], x);
					group.bounds[4] = Math.max(group.bounds[4], y);
					group.bounds[5] = Math.max(group.bounds[5], z);
				}
			}
		}

		// --- ObjectResource's own transform, then StoredModelResourceBase.transform -------
		// Both run on the ObjModel, i.e. on the shared source meshes, before any placement is
		// added. The model resource's transform runs first because the vehicle resource has to
		// load the model before it can transform it.
		applyTransform(groups, testCase.modelTranslation, testCase.modelRotation,
				testCase.modelScale, testCase.modelMirror);
		applyTransform(groups, testCase.schemaTranslation, testCase.schemaRotation,
				testCase.schemaScale, testCase.schemaMirror);

		// --- ObjModel.addTransformation per placement -------------------------
		final Model model = new Model();
		for (final Transform transform : testCase.transforms) {
			final OptimizedModel.ShaderType shaderType =
					OptimizedModel.ShaderType.values()[transform.shaderType];
			for (final GroupResult group : groups) {
				for (final Mesh mesh : group.meshes) {
					final Mesh placed = mesh.copyWithShader(shaderType);
					placed.raw.applyTranslation(
							(float) transform.x, (float) transform.y, (float) transform.z);
					if (transform.flipped) {
						placed.raw.applyRotation(
								new org.mtr.mapping.holder.Vector3f(0.0F, 1.0F, 0.0F), 180.0F);
					}
					model.append(placed);
				}
			}
		}

		final List<Mesh> finished = model.finish();

		// --- Pack exactly like RawMesh.upload ---------------------------------
		final ByteArrayOutputStream vertexBytes = new ByteArrayOutputStream();
		final ByteArrayOutputStream indexBytes = new ByteArrayOutputStream();
		final List<int[]> draws = new ArrayList<>();
		int vertexOffset = 0;
		int indexOffset = 0;

		for (final Mesh mesh : finished) {
			if (mesh.raw.faces.isEmpty() || mesh.raw.vertices.isEmpty()) {
				continue;
			}

			final int color = mesh.material.properties.vertexAttributeState.color == null
					? 0xFFFFFFFF : mesh.material.properties.vertexAttributeState.color;
			draws.add(new int[]{
					mesh.material.properties.shaderType.ordinal(),
					color,
					-1, // key index is resolved after the key table is known
					vertexOffset,
					mesh.raw.vertices.size(),
					indexOffset,
					mesh.raw.faces.size() * 3
			});

			for (final Vertex vertex : mesh.raw.vertices) {
				writeFloat(vertexBytes, vertex.position.getX());
				writeFloat(vertexBytes, vertex.position.getY());
				writeFloat(vertexBytes, vertex.position.getZ());
				writeFloat(vertexBytes, vertex.u);
				writeFloat(vertexBytes, vertex.v);
				final Vector3f normal = new Vector3f(
						vertex.normal.getX(), vertex.normal.getY(), vertex.normal.getZ());
				normal.normalize();
				vertexBytes.write((byte) (normal.x * 127.0F));
				vertexBytes.write((byte) (normal.y * 127.0F));
				vertexBytes.write((byte) (normal.z * 127.0F));
				vertexBytes.write(0);
			}

			for (final Face face : mesh.raw.faces) {
				for (final int index : face.vertices) {
					writeInt(indexBytes, index + vertexOffset);
				}
			}

			vertexOffset += mesh.raw.vertices.size();
			indexOffset += mesh.raw.faces.size() * 3;
		}

		// --- Key table: material group keys in OBJ order ----------------------
		// The native side reports the same list from wr_obj_keys_load, and the host
		// resolves texture handles in that order. Only keys that produced geometry
		// are kept, matching ObjSplitting skipping empty groups.
		final Map<String, CaseRecord> keyTable = new LinkedHashMap<>();
		for (final GroupResult group : groups) {
			for (final Map.Entry<String, CaseRecord> entry : group.records.entrySet()) {
				keyTable.putIfAbsent(entry.getKey(), entry.getValue());
			}
		}
		final List<String> keyTextures = new ArrayList<>(keyTable.keySet());
		for (final int[] draw : draws) {
			// Recover which key each bucket came from, by matching the finished mesh
			// order against the draw order.
			draw[2] = -1;
		}
		int drawIndex = 0;
		for (final Mesh mesh : finished) {
			if (mesh.raw.faces.isEmpty() || mesh.raw.vertices.isEmpty()) {
				continue;
			}
			final int keyIndex = keyTextures.indexOf(mesh.material.texture);
			if (drawIndex < draws.size()) {
				draws.get(drawIndex)[2] = keyIndex;
			}
			drawIndex++;
		}

		final byte[] vertexData = vertexBytes.toByteArray();
		final byte[] indexData = indexBytes.toByteArray();
		writeGolden(directory.resolve(testCase.name + ".golden"), keyTable, draws,
				vertexData, indexData, testCase.flipTextureV, testCase.materialGroupTextures);
		writeCase(directory.resolve(testCase.name + ".case"), testCase, objFile, mtlFile);

		System.out.printf("  OK   %-26s %7d vertices %7d indices %2d bucket(s) %2d key(s) 0x%016X%n",
				testCase.name, vertexData.length / 24, indexData.length / 4, draws.size(),
				keyTable.size(), checksum(vertexData, indexData));
	}

	/** ObjectResource.clampNumber: anything at or below zero becomes 1. */
	private static float clampScale(double value) {
		return (float) (value <= 0 ? 1 : value);
	}

	/**
	 * Applies one translation, rotation, scale and mirror group to every source mesh.
	 *
	 * <p>Mirrors {@code ObjectResource.transform}: the translation runs first, then the X, Y
	 * and Z rotations each in their own pass, then the scale — whose non-uniform branch
	 * corrects the normals — then the mirror, whose odd-parity branch reverses the winding.</p>
	 */
	private static void applyTransform(List<GroupResult> groups,
			double[] translation, double[] rotation, double[] scale, boolean[] mirror) {
		for (final GroupResult group : groups) {
			for (final Mesh mesh : group.meshes) {
				mesh.raw.applyTranslation((float) translation[0], (float) translation[1],
						(float) translation[2]);
				mesh.raw.applyRotation(new org.mtr.mapping.holder.Vector3f(1.0F, 0.0F, 0.0F),
						(float) rotation[0]);
				mesh.raw.applyRotation(new org.mtr.mapping.holder.Vector3f(0.0F, 1.0F, 0.0F),
						(float) rotation[1]);
				mesh.raw.applyRotation(new org.mtr.mapping.holder.Vector3f(0.0F, 0.0F, 1.0F),
						(float) rotation[2]);
				mesh.raw.applyScale(clampScale(scale[0]), clampScale(scale[1]), clampScale(scale[2]));
				mesh.raw.applyMirror(mirror[0], mirror[1], mirror[2], mirror[0], mirror[1], mirror[2]);
			}
		}
	}

	/**
	 * Mirrors ObjModelLoader.loadModel's inner body: split the group by material
	 * group, then build one RawMesh per surviving material group using the real
	 * ObjUtils.convertToRenderable.
	 */
	private static GroupResult loadGroup(
			Obj groupObj,
			Map<String, Mtl> materials,
			Function<String, String> textureResolver,
			boolean materialGroupTextures,
			boolean flipTextureV
	) {
		final GroupResult result = new GroupResult();
		final Map<String, Mtl> effectiveMaterials = materialGroupTextures ? materials : new HashMap<>();

		ObjSplitting.splitByMaterialGroups(groupObj).forEach((groupKey, materialObj) -> {
			if (materialObj.getNumFaces() == 0) {
				return;
			}

			final Map<String, String> options = splitMaterialOptions(groupKey);
			final String materialGroupName = options.get("");
			final OptimizedModel.ShaderType shaderType = legacyMapping(options.getOrDefault("#", ""));
			final boolean groupFlipV = "1".equals(options.getOrDefault("flipv", "0"));

			final String texture;
			final Integer color;
			if (!effectiveMaterials.isEmpty()) {
				final Mtl objMaterial = effectiveMaterials.get(groupKey);
				if (objMaterial == null) {
					texture = "";
					color = null;
				} else {
					texture = objMaterial.getMapKd() == null || objMaterial.getMapKd().isEmpty()
							? "" : objMaterial.getMapKd();
					final FloatTuple kd = objMaterial.getKd();
					color = kd == null
							? mergeColor(255, 255, 255, 255)
							: mergeColor(
									(int) (kd.getX() * 255.0F),
									(int) (kd.getY() * 255.0F),
									(int) (kd.getZ() * 255.0F),
									objMaterial.getD() == null
											? 255 : (int) (objMaterial.getD() * 255.0F));
				}
			} else {
				texture = materialGroupName.equals("_") ? "" : materialGroupName;
				color = mergeColor(255, 255, 255, 255);
			}

			final Obj renderable = ObjUtils.convertToRenderable(materialObj);
			final String resolvedTexture = textureResolver.apply(
					texture.replace("\\\\", "/").replace("\\", "/"));

			// MaterialProperties is constructible outside the client: the only
			// non-trivial branch in its constructor, the 3-argument one, is not taken.
			// The texture is null because Identifier needs a live resource manager;
			// resolvedTexture carries that identity instead.
			final MaterialProperties properties = new MaterialProperties(shaderType, null, color);
			final org.mtr.mapping.render.model.RawMesh mesh =
					new org.mtr.mapping.render.model.RawMesh(properties);

			for (int i = 0; i < renderable.getNumVertices(); i++) {
				final Vertex vertex = new Vertex();
				final FloatTuple position = renderable.getVertex(i);
				vertex.position = new org.mtr.mapping.holder.Vector3f(
						position.getX(), position.getY(), position.getZ());
				if (i < renderable.getNumNormals()) {
					final FloatTuple normal = renderable.getNormal(i);
					vertex.normal = new org.mtr.mapping.holder.Vector3f(
							normal.getX(), normal.getY(), normal.getZ());
				} else {
					vertex.normal = new org.mtr.mapping.holder.Vector3f(0.0F, 0.0F, 0.0F);
				}
				if (i < renderable.getNumTexCoords()) {
					final FloatTuple uv = renderable.getTexCoord(i);
					vertex.u = uv.getX();
					vertex.v = (groupFlipV || flipTextureV) ? 1.0F - uv.getY() : uv.getY();
				} else {
					vertex.u = 0.0F;
					vertex.v = 0.0F;
				}
				mesh.vertices.add(vertex);
			}

			for (int i = 0; i < renderable.getNumFaces(); i++) {
				final ObjFace face = renderable.getFace(i);
				mesh.faces.add(new Face(new int[]{
						face.getVertexIndex(0), face.getVertexIndex(1), face.getVertexIndex(2)}));
			}

			mesh.validateVertexIndex();

			result.meshes.add(new Mesh(new MaterialKey(properties, resolvedTexture), mesh));
			result.records.putIfAbsent(resolvedTexture, new CaseRecord(
					groupKey, shaderType, groupFlipV || flipTextureV, resolvedTexture,
					color == null ? 0xFFFFFFFF : color));
		});

		return result;
	}

	// -----------------------------------------------------------------------
	// Output helpers
	// -----------------------------------------------------------------------

	private static void writeFloat(ByteArrayOutputStream stream, float value) {
		final int bits = Float.floatToRawIntBits(value);
		stream.write(bits & 0xFF);
		stream.write((bits >>> 8) & 0xFF);
		stream.write((bits >>> 16) & 0xFF);
		stream.write((bits >>> 24) & 0xFF);
	}

	private static void writeInt(ByteArrayOutputStream stream, int value) {
		stream.write(value & 0xFF);
		stream.write((value >>> 8) & 0xFF);
		stream.write((value >>> 16) & 0xFF);
		stream.write((value >>> 24) & 0xFF);
	}

	private static long checksum(byte[] vertexData, byte[] indexData) {
		long hash = 0xCBF29CE484222325L;
		for (final byte value : vertexData) {
			hash ^= value & 0xFF;
			hash *= 0x100000001B3L;
		}
		for (final byte value : indexData) {
			hash ^= value & 0xFF;
			hash *= 0x100000001B3L;
		}
		return hash;
	}

	private static void writeGolden(
			Path path,
			Map<String, CaseRecord> keys,
			List<int[]> draws,
			byte[] vertexData,
			byte[] indexData,
			boolean flipTextureV,
			boolean materialGroupTextures
	) throws IOException {
		final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
		final DataOutputStream out = new DataOutputStream(buffer);

		out.write("WROBJGLD".getBytes(StandardCharsets.US_ASCII));
		out.writeInt(MAGIC_VERSION);
		out.writeInt(keys.size());
		out.writeInt(draws.size());
		out.writeLong(vertexData.length);
		out.writeLong(indexData.length);
		int flags = 0;
		if (flipTextureV) {
			flags |= 1;
		}
		if (materialGroupTextures) {
			flags |= 2;
		}
		out.writeInt(flags);
		out.writeInt(0);

		for (final CaseRecord key : keys.values()) {
			out.writeInt(key.shaderType.ordinal());
			out.writeInt(key.color);
			final byte[] texture = key.texture.getBytes(StandardCharsets.UTF_8);
			out.writeInt(texture.length);
			out.write(texture);
		}

		for (final int[] draw : draws) {
			for (final int field : draw) {
				out.writeInt(field);
			}
		}

		out.write(vertexData);
		out.write(indexData);
		out.writeLong(checksum(vertexData, indexData));
		out.flush();

		Files.write(path, buffer.toByteArray());
	}

	private static void writeCase(Path path, Case testCase, Path objFile, Path mtlFile) throws IOException {
		final StringBuilder builder = new StringBuilder();
		builder.append("# generated by GenerateGolden.java - do not edit\n");
		builder.append("name=").append(testCase.name).append('\n');
		builder.append("obj=").append(objFile.getFileName()).append('\n');
		if (mtlFile != null) {
			builder.append("mtl=").append(mtlFile.getFileName()).append('\n');
		}
		builder.append("golden=").append(testCase.name).append(".golden\n");
		if (testCase.flipTextureV) {
			builder.append("flipTextureV=1\n");
		}
		if (!testCase.materialGroupTextures) {
			builder.append("materialGroupTextures=0\n");
		}
		final List<String> transformLines = new ArrayList<>();
		for (final Transform transform : testCase.transforms) {
			transformLines.add(transform.toCaseLine());
		}
		builder.append("transforms=").append(String.join(";", transformLines)).append('\n');
		Files.write(path, builder.toString().getBytes(StandardCharsets.UTF_8));
	}

	private GenerateGolden() {
	}
}
