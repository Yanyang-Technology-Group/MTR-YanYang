package org.mtr.mod.resource;

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.Object2ObjectAVLTreeMap;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.mtr.mapping.holder.Identifier;
import org.mtr.mapping.mapper.OptimizedModel;
import org.mtr.mod.Init;
import org.mtr.mod.resource.nativeobj.NativeObjModel;
import org.mtr.mod.resource.nativeobj.NativeObjModelAdapter;
import org.mtr.mod.resource.nativeobj.NativeObjResource;

import javax.annotation.Nullable;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.function.Function;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public final class ModelResourceLoader {

	/**
	 * Which backend built a loaded model.
	 *
	 * <p>Kept alongside the map so the caller does not have to guess from the map's contents,
	 * and so a diagnostic can state plainly which path ran.</p>
	 */
	public enum Backend {
		/** The original Java pipeline: javagl parsing into {@code OptimizedModel.ObjModel}. */
		JAVA,
		/** The native pipeline: OBJ and MTL parsing in C++, geometry in native memory. */
		NATIVE
	}

	/**
	 * The result of loading one model resource.
	 *
	 * <p>{@link #models} is typed as {@link OptimizedObjModel} so both backends fit in one
	 * container, which is what lets {@code DynamicVehicleModel} and
	 * {@code ModelPropertiesPart} stay backend-agnostic.</p>
	 */
	public static final class LoadedModels {

		/**
		 * The models, keyed by the group names MTR addresses parts by.
		 *
		 * <p>Typed as the interface rather than a concrete class so both backends fit in one
		 * container. Callers that need the plain {@code Collection<OptimizedObjModel>} that
		 * {@code StoredModelResourceBase.transform} takes can copy the map, which is what the
		 * load path does: the wildcard keeps this field honest about only ever exposing models,
		 * and the copy is a few references.</p>
		 */
		public final Object2ObjectAVLTreeMap<String, ? extends OptimizedObjModel> models;
		public final Backend backend;

		private LoadedModels(Object2ObjectAVLTreeMap<String, ? extends OptimizedObjModel> models, Backend backend) {
			this.models = models;
			this.backend = backend;
		}

		/**
		 * The models as a plain typed map, for the transform call.
		 *
		 * <p>Widening the wildcard here rather than at the declaration keeps the field
		 * read-only in practice: a caller cannot put a Java model into a map that the native
		 * path filled.</p>
		 */
		public Object2ObjectAVLTreeMap<String, OptimizedObjModel> toTypedMap() {
			final Object2ObjectAVLTreeMap<String, OptimizedObjModel> copy = new Object2ObjectAVLTreeMap<>();
			copy.putAll(models);
			return copy;
		}
	}

	public static boolean isSupportedModelResource(String modelResource) {
		return modelResource.endsWith(".obj") || modelResource.endsWith(".mqo") || modelResource.endsWith(".mqoz");
	}

	/**
	 * Loads a model resource with the native pipeline when it is available, and with the
	 * original Java pipeline otherwise.
	 *
	 * <p>The native path deliberately never builds an {@code OptimizedModel.ObjModel}: doing so
	 * would mean constructing the very vertex objects the rewrite exists to avoid, so the
	 * saving would be lost. That is why this returns a backend-tagged result rather than a map
	 * of one concrete type.</p>
	 */
	public static LoadedModels loadModelAnyBackend(
			String modelResource,
			Identifier textureId,
			boolean flipTextureV,
			ResourceProvider resourceProvider
	) {
		if (NativeObjResource.isAvailable()) {
			final LoadedModels nativeModels = loadModelNative(modelResource, textureId, flipTextureV, resourceProvider);
			if (nativeModels != null) {
				return nativeModels;
			}
			// A model the native parser rejected is a resource problem, not a reason to lose the
			// model entirely: the Java pipeline is more permissive about malformed input, so
			// falling through to it is the graceful outcome.
			Init.LOGGER.warn("[wrobj] the native pipeline could not load {}, using the Java OBJ pipeline",
					modelResource);
		}

		final Object2ObjectAVLTreeMap<String, OptimizedObjModel> models = new Object2ObjectAVLTreeMap<>();
		loadModel(modelResource, textureId, flipTextureV, resourceProvider)
				.forEach((key, value) -> models.put(key, new JavaObjModel(value)));
		return new LoadedModels(models, Backend.JAVA);
	}

	/**
	 * The original loader, unchanged: parses through javagl and uploads through
	 * {@code OptimizedModel.ObjModel}.
	 */
	public static Object2ObjectAVLTreeMap<String, OptimizedModel.ObjModel> loadModel(
			String modelResource,
			Identifier textureId,
			boolean flipTextureV,
			ResourceProvider resourceProvider
	) {
		if (modelResource.endsWith(".mqo") || modelResource.endsWith(".mqoz")) {
			final MqoModelConverter.ConvertedModel convertedModel = MqoModelConverter.convert(getMqoContent(modelResource, resourceProvider));
			return new Object2ObjectAVLTreeMap<>(OptimizedModel.ObjModel.loadModel(
					convertedModel.getObjContent(),
					mtlString -> convertedModel.getMtlContent(),
					textureString -> StringUtils.isEmpty(textureString) ? OptimizedModelWrapper.WHITE_TEXTURE : StringUtils.equals(textureString, "default.png") ? textureId : CustomResourceTools.getResourceFromSamePath(modelResource, textureString, "png"),
					null, true, flipTextureV
			));
		} else {
			return new Object2ObjectAVLTreeMap<>(OptimizedModel.ObjModel.loadModel(
					resourceProvider.get(CustomResourceTools.formatIdentifierWithDefault(modelResource, "obj")),
					mtlString -> resourceProvider.get(CustomResourceTools.getResourceFromSamePath(modelResource, mtlString, "mtl")),
					textureString -> StringUtils.isEmpty(textureString) ? OptimizedModelWrapper.WHITE_TEXTURE : StringUtils.equals(textureString, "default.png") ? textureId : CustomResourceTools.getResourceFromSamePath(modelResource, textureString, "png"),
					null, true, flipTextureV
			));
		}
	}

	/**
	 * The native loader.
	 *
	 * <p>Keys are recovered by running the native parser twice — once for the key list and once
	 * inside the builder — which is cheap because key discovery discards the geometry it walks.
	 * The two runs agree by construction: both split the document the same way, so the key
	 * order is the same and the map keys line up with the texture table.</p>
	 *
	 * @return the models, or null when the native pipeline cannot handle this resource
	 */
	@Nullable
	private static LoadedModels loadModelNative(
			String modelResource,
			Identifier textureId,
			boolean flipTextureV,
			ResourceProvider resourceProvider
	) {
		final Object2ObjectAVLTreeMap<String, OptimizedObjModel> models = new Object2ObjectAVLTreeMap<>();

		if (modelResource.endsWith(".mqo") || modelResource.endsWith(".mqoz")) {
			// MQO is converted to OBJ text by the existing converter, then handed to the native
			// parser. The conversion itself is unchanged, so an MQO model and an equivalent OBJ
			// model still take the same path from here on.
			final MqoModelConverter.ConvertedModel convertedModel;
			try {
				convertedModel = MqoModelConverter.convert(getMqoContent(modelResource, resourceProvider));
			} catch (Throwable throwable) {
				Init.LOGGER.warn("[wrobj] could not convert {}: {}", modelResource, throwable.toString());
				return null;
			}
			return buildNative(convertedModel.getObjContent(), convertedModel.getMtlContent(),
					modelResource, textureId, flipTextureV, models);
		}

		final String objContent;
		try {
			objContent = resourceProvider.get(CustomResourceTools.formatIdentifierWithDefault(modelResource, "obj"));
		} catch (Throwable throwable) {
			return null;
		}
		if (StringUtils.isEmpty(objContent)) {
			return null;
		}

		// The MTL is resolved only when the OBJ header names one, mirroring ObjReader keeping a
		// single mtllib name. A model without an MTL is legitimate and stays on the same path.
		final String mtlName = NativeObjResource.findLastMtlLib(objContent);
		String mtlContent = null;
		if (!StringUtils.isEmpty(mtlName)) {
			try {
				mtlContent = resourceProvider.get(CustomResourceTools.getResourceFromSamePath(
						modelResource, mtlName.replace("\\\\", "/").replace("\\", "/"), "mtl"));
			} catch (Throwable throwable) {
				Init.LOGGER.warn("[wrobj] the MTL of {} could not be read; the model will load untextured",
						modelResource);
			}
		}

		return buildNative(objContent, mtlContent, modelResource, textureId, flipTextureV, models);
	}

	@Nullable
	private static LoadedModels buildNative(
			String objContent,
			@Nullable String mtlContent,
			String modelResource,
			Identifier textureId,
			boolean flipTextureV,
			Object2ObjectAVLTreeMap<String, OptimizedObjModel> models
	) {
		final NativeObjModel nativeModel = NativeObjResource.buildFromContent(
				objContent, mtlContent, modelResource, textureId, flipTextureV);
		if (nativeModel == null) {
			return null;
		}

		// One entry per model group, so a multi-group OBJ behaves exactly as it does on the Java
		// path: MTR addresses parts by group name.
		final String[] groupNames = getModelGroupNames(objContent);
		if (groupNames.length == 0) {
			// No `g` statement at all: the whole document is the implicit "default" group, which
			// is the same single-entry map ObjSplitting.splitByGroups produces.
			models.put("", new NativeObjModelAdapter(nativeModel));
		} else {
			for (final String groupName : groupNames) {
				models.put(groupName, new NativeObjModelAdapter(nativeModel));
			}
		}

		return new LoadedModels(models, Backend.NATIVE);
	}

	/**
	 * The group names a document declares, in first-activation order.
	 *
	 * <p>Derived from the document text rather than from the native side, because the group
	 * split is what MTR addresses parts by and the same names have to appear whether the
	 * geometry came from C++ or from javagl. The rules mirror {@code ObjReader}: a single
	 * {@code g}/{@code o} state machine, so an {@code o} after a {@code g} is ignored.</p>
	 */
	private static String[] getModelGroupNames(String objContent) {
		final java.util.LinkedHashSet<String> names = new java.util.LinkedHashSet<>();
		String groupingKeyword = null;
		for (final String rawLine : objContent.split("\r\n|\r|\n")) {
			final String line = rawLine.trim();
			if (line.length() < 2) {
				continue;
			}
			final char first = Character.toLowerCase(line.charAt(0));
			if (first != 'g' && first != 'o') {
				continue;
			}
			if (line.charAt(1) != ' ' && line.charAt(1) != '\t') {
				continue;
			}
			final String keyword = String.valueOf(first);
			if (groupingKeyword != null && !groupingKeyword.equals(keyword)) {
				// ObjReader keeps one keyword: a different one is ignored until it appears again.
				continue;
			}
			groupingKeyword = keyword;
			final String remainder = line.substring(1).trim();
			if (remainder.isEmpty()) {
				names.add("default");
			} else {
				for (final String name : remainder.split("\\s+")) {
					names.add(name);
				}
			}
		}
		// ObjSplitting skips groups with no faces, but a group the document declared and never
		// populated would leave the tree map missing rather than lying about its contents, so
		// the names are returned as declared and the empty ones simply never get looked up.
		return names.toArray(new String[0]);
	}

	public static ObjectArrayList<String> getModelParts(String name, String content) {
		if (name.endsWith(".mqo")) {
			return MqoModelConverter.convert(content).getModelParts();
		} else if (name.endsWith(".mqoz")) {
			return getModelParts(name, content.getBytes(StandardCharsets.UTF_8));
		} else {
			return new ObjectArrayList<>(OptimizedModel.ObjModel.loadModel(content, mtlString -> "", textureString -> new Identifier(""), null, true, false).keySet());
		}
	}

	public static ObjectArrayList<String> getModelParts(String name, byte[] bytes) {
		if (name.endsWith(".mqoz")) {
			return MqoModelConverter.convert(extractMqoContentFromMqoz(name, bytes)).getModelParts();
		} else {
			return getModelParts(name, new String(bytes, StandardCharsets.UTF_8));
		}
	}

	public static String extractMqoContentFromMqoz(String modelResource, byte[] bytes) {
		final ObjectArrayList<MqozEntry> mqoEntries = new ObjectArrayList<>();
		try (final ZipInputStream zipInputStream = new ZipInputStream(new ByteArrayInputStream(bytes))) {
			ZipEntry zipEntry;
			while ((zipEntry = zipInputStream.getNextEntry()) != null) {
				if (!zipEntry.isDirectory() && zipEntry.getName().toLowerCase().endsWith(".mqo")) {
					mqoEntries.add(new MqozEntry(zipEntry.getName(), new String(IOUtils.toByteArray(zipInputStream), StandardCharsets.UTF_8)));
				}
				zipInputStream.closeEntry();
			}
		} catch (Exception e) {
			throw new IllegalArgumentException("Invalid MQOZ archive", e);
		}

		if (mqoEntries.isEmpty()) {
			throw new IllegalArgumentException("MQOZ archive does not contain an MQO file");
		}

		final String modelBaseName = getFileBaseName(modelResource);
		mqoEntries.sort(Comparator.comparing(entry -> entry.name));
		for (final MqozEntry entry : mqoEntries) {
			if (StringUtils.equals(getFileBaseName(entry.name), modelBaseName)) {
				return entry.content;
			}
		}
		return mqoEntries.get(0).content;
	}

	private static String getMqoContent(String modelResource, ResourceProvider resourceProvider) {
		if (modelResource.endsWith(".mqoz")) {
			return extractMqoContentFromMqoz(modelResource, resourceProvider.getBytes(CustomResourceTools.formatIdentifierWithDefault(modelResource, "mqoz")));
		} else {
			return resourceProvider.get(CustomResourceTools.formatIdentifierWithDefault(modelResource, "mqo"));
		}
	}

	private static String getFileBaseName(String path) {
		String normalizedPath = path.replace('\\', '/');
		final int namespaceIndex = normalizedPath.indexOf(':');
		if (namespaceIndex >= 0) {
			normalizedPath = normalizedPath.substring(namespaceIndex + 1);
		}
		final String[] pathSplit = normalizedPath.split("/");
		final String fileName = pathSplit[pathSplit.length - 1];
		final int extensionIndex = fileName.lastIndexOf('.');
		return extensionIndex < 0 ? fileName : fileName.substring(0, extensionIndex);
	}

	private ModelResourceLoader() {
	}

	private static final class MqozEntry {

		private final String name;
		private final String content;

		private MqozEntry(String name, String content) {
			this.name = name;
			this.content = content;
		}
	}
}
