package com.assetmanager.core.mesh;

/** Indexed triangle mesh in object space. Attribute arrays may be null (renderer degrades gracefully). */
public final class Mesh {

    public String name = "";
    public float[] positions;   // 3 floats per vertex
    public float[] normals;     // 3 floats per vertex, optional
    public float[] uvs;         // 2 floats per vertex, optional
    public float[] colors;      // 4 floats per vertex, optional
    public int[] indices;       // 3 per triangle
    public Material material = new Material();

    public int vertexCount()  { return positions == null ? 0 : positions.length / 3; }
    public int triangleCount(){ return indices == null ? 0 : indices.length / 3; }

    public boolean hasNormals() { return normals != null && normals.length >= positions.length; }
    public boolean hasUVs()     { return uvs != null && uvs.length >= (positions.length / 3) * 2; }
    public boolean hasColors()  { return colors != null && colors.length >= (positions.length / 3) * 4; }

    /** Recomputes area-weighted vertex normals when the file has none. */
    public void generateNormals() {
        int vc = vertexCount();
        if (vc == 0 || indices == null) return;
        float[] n = new float[vc * 3];
        for (int i = 0; i + 2 < indices.length; i += 3) {
            int a = indices[i], b = indices[i + 1], c = indices[i + 2];
            if (a < 0 || b < 0 || c < 0 || a >= vc || b >= vc || c >= vc) continue;
            float ax = positions[a*3],   ay = positions[a*3+1],   az = positions[a*3+2];
            float bx = positions[b*3],   by = positions[b*3+1],   bz = positions[b*3+2];
            float cx = positions[c*3],   cy = positions[c*3+1],   cz = positions[c*3+2];
            float ux = bx-ax, uy = by-ay, uz = bz-az;
            float vx = cx-ax, vy = cy-ay, vz = cz-az;
            float nx = uy*vz - uz*vy, ny = uz*vx - ux*vz, nz = ux*vy - uy*vx;
            for (int v : new int[] { a, b, c }) {
                n[v*3] += nx; n[v*3+1] += ny; n[v*3+2] += nz;
            }
        }
        for (int v = 0; v < vc; v++) {
            float l = (float) Math.sqrt(n[v*3]*n[v*3] + n[v*3+1]*n[v*3+1] + n[v*3+2]*n[v*3+2]);
            if (l > 1e-12f) { n[v*3] /= l; n[v*3+1] /= l; n[v*3+2] /= l; }
            else { n[v*3] = 0; n[v*3+1] = 1; n[v*3+2] = 0; }
        }
        normals = n;
    }

    @Override public String toString() {
        return "Mesh[" + name + " v=" + vertexCount() + " t=" + triangleCount() + " mat=" + material.name + "]";
    }
}
