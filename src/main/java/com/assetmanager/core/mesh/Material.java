package com.assetmanager.core.mesh;

import java.awt.image.BufferedImage;
import java.util.LinkedHashMap;
import java.util.Map;

/** Simplified PBR-ish material shared by OBJ/MTL and glTF. */
public final class Material {

    public String name = "default";

    public float[] diffuse  = { 0.82f, 0.82f, 0.82f };
    public float[] ambient  = { 0.15f, 0.15f, 0.15f };
    public float[] specular = { 0.30f, 0.30f, 0.30f };
    public float[] emissive = { 0f, 0f, 0f };

    public float alpha = 1f;
    public int   shininess = 24;
    public boolean doubleSided = true;
    public boolean unlit = false;

    public float metallic = 0f;
    public float roughness = 0.7f;

    public String texturePath;
    public String normalMapPath;
    public String emissiveMapPath;

    private BufferedImage texture;
    private boolean textureTried;

    public Material() {}

    public Material copy() {
        Material m = new Material();
        m.name = name;
        m.diffuse = diffuse.clone();
        m.ambient = ambient.clone();
        m.specular = specular.clone();
        m.emissive = emissive.clone();
        m.alpha = alpha; m.shininess = shininess;
        m.doubleSided = doubleSided; m.unlit = unlit;
        m.metallic = metallic; m.roughness = roughness;
        m.texturePath = texturePath; m.normalMapPath = normalMapPath; m.emissiveMapPath = emissiveMapPath;
        m.texture = texture; m.textureTried = textureTried;
        return m;
    }

    public BufferedImage texture() { return texture; }
    public void setTexture(BufferedImage bi) { this.texture = bi; this.textureTried = true; }
    public boolean textureResolved() { return textureTried; }
    public void markTextureUnresolved() { textureTried = false; }

    /** glTF alphaMode */
    public String alphaMode = "OPAQUE";
    public double alphaCutoff = 0.5;

    public static Map<String, Material> defaults() { return new LinkedHashMap<>(); }

    @Override public String toString() { return name; }
}
