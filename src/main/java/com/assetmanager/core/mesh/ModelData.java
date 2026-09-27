package com.assetmanager.core.mesh;

import com.assetmanager.util.Mat4;

import java.util.ArrayList;
import java.util.List;

/** A loaded model: one or more meshes, already flattened into world space. */
public final class ModelData {

    public final List<Mesh> meshes = new ArrayList<>();
    public String sourceName = "";
    public final List<String> warnings = new ArrayList<>();

    public float[] min = { Float.MAX_VALUE,  Float.MAX_VALUE,  Float.MAX_VALUE };
    public float[] max = { -Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE };
    public boolean boundsValid = false;

    public int vertexCount()   { return meshes.stream().mapToInt(Mesh::vertexCount).sum(); }
    public int triangleCount() { return meshes.stream().mapToInt(Mesh::triangleCount).sum(); }
    public int materialCount() { return (int) meshes.stream().map(m -> m.material).distinct().count(); }

    public void computeBounds() {
        min = new float[] { Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE };
        max = new float[] { -Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE };
        boolean any = false;
        for (Mesh m : meshes) {
            if (m.positions == null) continue;
            for (int i = 0; i + 2 < m.positions.length; i += 3) {
                for (int k = 0; k < 3; k++) {
                    float v = m.positions[i + k];
                    if (v < min[k]) min[k] = v;
                    if (v > max[k]) max[k] = v;
                }
                any = true;
            }
        }
        boundsValid = any;
    }

    public float[] center() {
        if (!boundsValid) return new float[] { 0, 0, 0 };
        return new float[] { (min[0]+max[0])/2f, (min[1]+max[1])/2f, (min[2]+max[2])/2f };
    }

    public float radius() {
        if (!boundsValid) return 1f;
        float r = 0;
        float[] c = center();
        for (float x : new float[] { min[0], max[0] })
            for (float y : new float[] { min[1], max[1] })
                for (float z : new float[] { min[2], max[2] }) {
                    float d = (float) Math.sqrt((x-c[0])*(x-c[0]) + (y-c[1])*(y-c[1]) + (z-c[2])*(z-c[2]));
                    if (d > r) r = d;
                }
        return r < 1e-6f ? 1f : r;
    }

    /** Applies a matrix to every mesh position/normal in place. */
    public void transform(Mat4 t) {
        for (Mesh m : meshes) {
            if (m.positions != null) {
                for (int i = 0; i + 2 < m.positions.length; i += 3) {
                    float[] p = t.transformPoint(m.positions[i], m.positions[i+1], m.positions[i+2]);
                    m.positions[i] = p[0]; m.positions[i+1] = p[1]; m.positions[i+2] = p[2];
                }
            }
            if (m.normals != null) {
                float[] nm = Mat4.normalMatrix(t);
                for (int i = 0; i + 2 < m.normals.length; i += 3) {
                    float x = m.normals[i], y = m.normals[i+1], z = m.normals[i+2];
                    float nx = nm[0]*x + nm[1]*y + nm[2]*z;
                    float ny = nm[3]*x + nm[4]*y + nm[5]*z;
                    float nz = nm[6]*x + nm[7]*y + nm[8]*z;
                    float l = (float) Math.sqrt(nx*nx + ny*ny + nz*nz);
                    if (l > 1e-12f) { m.normals[i] = nx/l; m.normals[i+1] = ny/l; m.normals[i+2] = nz/l; }
                }
            }
        }
        computeBounds();
    }

    /** Drops meshes with no triangles so the renderer doesn't waste time. */
    public void pruneEmpty() {
        meshes.removeIf(m -> m.positions == null || m.indices == null || m.indices.length < 3);
    }

    public void warn(String w) {
        if (w != null && !w.isBlank() && warnings.size() < 100) warnings.add(w);
    }
}
