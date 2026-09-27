package com.assetmanager.core.mesh;

import com.assetmanager.util.Formats;
import com.assetmanager.util.Log;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;

/** Entry point for 3D loading. */
public final class MeshIo {

    /** Hard cap so a 200 MB mesh can't lock up the UI. */
    public static final long MAX_FILE_BYTES = 512L * 1024 * 1024;

    private MeshIo() {}

    public static boolean supports(Path p) {
        return Formats.isModelLoadable(Formats.extOf(p.getFileName().toString()));
    }

    public static ModelData load(Path file) throws IOException {
        if (!supports(file)) throw new IOException("Unsupported model format: " + file.getFileName());
        long size = file.toFile().length();
        if (size > MAX_FILE_BYTES)
            throw new IOException("File too large to preview (" + Formats.humanSize(size) + ")");

        String ext = Formats.extOf(file.getFileName().toString());
        long t0 = System.currentTimeMillis();
        ModelData m;
        switch (ext) {
            case "obj":            m = ObjLoader.load(file); break;
            case "gltf": case "glb": m = GltfLoader.load(file); break;
            case "stl":            m = SimpleMeshLoaders.loadStl(file); break;
            case "ply":            m = SimpleMeshLoaders.loadPly(file); break;
            default: throw new IOException("No loader for ." + ext);
        }

        // glTF resolves textures during load; OBJ/MTL still needs them fetched.
        for (Mesh mesh : m.meshes) resolveTexture(mesh.material);

        Log.info("Loaded " + file.getFileName() + ": " + m.vertexCount() + " verts, "
                + m.triangleCount() + " tris in " + (System.currentTimeMillis() - t0) + "ms");
        return m;
    }

    private static void resolveTexture(Material mat) {
        // "(embedded)" is the marker GltfLoader leaves when it already decoded the image itself
        if (mat.texturePath == null || mat.texturePath.startsWith("(")) return;
        if (mat.textureResolved()) return;
        BufferedImage bi = ObjLoader.loadTexture(mat.texturePath);
        if (bi != null) mat.setTexture(bi);
        else mat.markTextureUnresolved();
    }
}
