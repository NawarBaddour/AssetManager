package com.assetmanager.util;

import java.util.Arrays;

/** Column-major 4x4 matrices, same memory layout as OpenGL (m[col*4 + row]). */
public final class Mat4 {

    public final float[] m = new float[16];

    /** Creates an identity matrix. */
    public Mat4() {
        m[0] = m[5] = m[10] = m[15] = 1f;
    }

    public static Mat4 identity() { return new Mat4(); }

    public static Mat4 of(float... v) {
        Mat4 r = new Mat4();
        System.arraycopy(v, 0, r.m, 0, 16);
        return r;
    }

    public Mat4 set(float[] v) { System.arraycopy(v, 0, m, 0, 16); return this; }

    public Mat4 copy() { return new Mat4().set(m); }

    /** this = a * b (apply b first, then a). */
    public Mat4 mul(Mat4 b) {
        float[] a = this.m, o = new float[16];
        for (int c = 0; c < 4; c++) {
            for (int r = 0; r < 4; r++) {
                o[c * 4 + r] = a[r]     * b.m[c * 4]
                             + a[4 + r]  * b.m[c * 4 + 1]
                             + a[8 + r]  * b.m[c * 4 + 2]
                             + a[12 + r] * b.m[c * 4 + 3];
            }
        }
        System.arraycopy(o, 0, m, 0, 16);
        return this;
    }

    public static Mat4 multiply(Mat4 a, Mat4 b) { return a.copy().mul(b); }

    public static Mat4 translation(float x, float y, float z) {
        Mat4 r = identity();
        r.m[12] = x; r.m[13] = y; r.m[14] = z;
        return r;
    }

    public static Mat4 scaling(float x, float y, float z) {
        Mat4 r = identity();
        r.m[0] = x; r.m[5] = y; r.m[10] = z;
        return r;
    }

    public static Mat4 rotationX(float a) {
        float s = (float) Math.sin(a), c = (float) Math.cos(a);
        Mat4 r = identity();
        r.m[5] = c; r.m[6] = s; r.m[9] = -s; r.m[10] = c;
        return r;
    }

    public static Mat4 rotationY(float a) {
        float s = (float) Math.sin(a), c = (float) Math.cos(a);
        Mat4 r = identity();
        r.m[0] = c; r.m[2] = -s; r.m[8] = s; r.m[10] = c;
        return r;
    }

    public static Mat4 rotationZ(float a) {
        float s = (float) Math.sin(a), c = (float) Math.cos(a);
        Mat4 r = identity();
        r.m[0] = c; r.m[1] = s; r.m[4] = -s; r.m[5] = c;
        return r;
    }

    /** Mirror across the YZ plane; flips triangle winding. */
    public static Mat4 mirrorX() { return scaling(-1, 1, 1); }

    public float[] transform(float x, float y, float z, float w) {
        return new float[] {
            m[0] * x + m[4] * y + m[8]  * z + m[12] * w,
            m[1] * x + m[5] * y + m[9]  * z + m[13] * w,
            m[2] * x + m[6] * y + m[10] * z + m[14] * w,
            m[3] * x + m[7] * y + m[11] * z + m[15] * w
        };
    }

    public float[] transformPoint(float x, float y, float z) {
        float[] r = transform(x, y, z, 1f);
        if (r[3] != 0 && r[3] != 1) { r[0] /= r[3]; r[1] /= r[3]; r[2] /= r[3]; }
        return r;
    }

    public float[] transformVector(float x, float y, float z) {
        float[] r = transform(x, y, z, 0f);
        return new float[] { r[0], r[1], r[2] };
    }

    public static float[] transformVector(Mat4 t, float x, float y, float z) {
        return t.transformVector(x, y, z);
    }

    public static float[] transform(Mat4 t, float x, float y, float z) { return t.transformPoint(x, y, z); }

    public static float[] normalMatrix(Mat4 model) {
        Mat4 inv = invert(model);
        if (inv == null) return new float[] { 0, 0, 1 };
        // transpose of upper-left 3x3
        return new float[] {
            inv.m[0], inv.m[4], inv.m[8],
            inv.m[1], inv.m[5], inv.m[9],
            inv.m[2], inv.m[6], inv.m[10]
        };
    }

    public static Mat4 perspective(float fovYRadians, float aspect, float zNear, float zFar) {
        float f = (float) (1.0 / Math.tan(fovYRadians / 2.0));
        Mat4 r = new Mat4();
        r.m[0] = f / aspect;
        r.m[5] = f;
        r.m[10] = (zFar + zNear) / (zNear - zFar);
        r.m[11] = -1f;
        r.m[14] = (2 * zFar * zNear) / (zNear - zFar);
        r.m[15] = 0;
        return r;
    }

    public static Mat4 lookAt(float[] eye, float[] center, float[] up) {
        float[] f = norm(sub(center, eye));
        if (Float.isNaN(f[0])) f = new float[] { 0, 0, -1 };
        float[] s = norm(cross(f, up));
        if (Float.isNaN(s[0]) || (s[0] == 0 && s[1] == 0 && s[2] == 0)) s = new float[] { 1, 0, 0 };
        float[] u = cross(s, f);
        Mat4 r = identity();
        r.m[0] = s[0]; r.m[4] = s[1]; r.m[8]  = s[2];
        r.m[1] = u[0]; r.m[5] = u[1]; r.m[9]  = u[2];
        r.m[2] = -f[0]; r.m[6] = -f[1]; r.m[10] = -f[2];
        r.m[12] = -dot(s, eye);
        r.m[13] = -dot(u, eye);
        r.m[14] =  dot(f, eye);
        return r;
    }

    public static Mat4 invert(Mat4 a) {
        float[] m = a.m;
        float[] inv = new float[16];
        inv[0]  =  m[5]*m[10]*m[15] - m[5]*m[11]*m[14] - m[9]*m[6]*m[15] + m[9]*m[7]*m[14] + m[13]*m[6]*m[11] - m[13]*m[7]*m[10];
        inv[4]  = -m[4]*m[10]*m[15] + m[4]*m[11]*m[14] + m[8]*m[6]*m[15] - m[8]*m[7]*m[14] - m[12]*m[6]*m[11] + m[12]*m[7]*m[10];
        inv[8]  =  m[4]*m[9]  *m[15] - m[4]*m[11]*m[13] - m[8]*m[5]*m[15] + m[8]*m[7]*m[13] + m[12]*m[5]*m[11] - m[12]*m[7]*m[9];
        inv[12] = -m[4]*m[9]  *m[14] + m[4]*m[10]*m[13] + m[8]*m[5]*m[14] - m[8]*m[6]*m[13] - m[12]*m[5]*m[10] + m[12]*m[6]*m[9];
        inv[1]  = -m[1]*m[10]*m[15] + m[1]*m[11]*m[14] + m[9]*m[2]*m[15] - m[9]*m[3]*m[14] - m[13]*m[2]*m[11] + m[13]*m[3]*m[10];
        inv[5]  =  m[0]*m[10]*m[15] - m[0]*m[11]*m[14] - m[8]*m[2]*m[15] + m[8]*m[3]*m[14] + m[12]*m[2]*m[11] - m[12]*m[3]*m[10];
        inv[9]  = -m[0]*m[9]  *m[15] + m[0]*m[11]*m[13] + m[8]*m[1]*m[15] - m[8]*m[3]*m[13] - m[12]*m[1]*m[11] + m[12]*m[3]*m[9];
        inv[13] =  m[0]*m[9]  *m[14] - m[0]*m[10]*m[13] - m[8]*m[1]*m[14] + m[8]*m[2]*m[13] + m[12]*m[1]*m[10] - m[12]*m[2]*m[9];
        inv[2]  =  m[1]*m[6]  *m[15] - m[1]*m[7]  *m[14] - m[5]*m[2]*m[15] + m[5]*m[3]*m[14] + m[13]*m[2]*m[7]  - m[13]*m[3]*m[6];
        inv[6]  = -m[0]*m[6]  *m[15] + m[0]*m[7]  *m[14] + m[4]*m[2]*m[15] - m[4]*m[3]*m[14] - m[12]*m[2]*m[7]  + m[12]*m[3]*m[6];
        inv[10] =  m[0]*m[5]  *m[15] - m[0]*m[7]  *m[13] - m[4]*m[1]*m[15] + m[4]*m[3]*m[13] + m[12]*m[1]*m[7]  - m[12]*m[3]*m[5];
        inv[14] = -m[0]*m[5]  *m[14] + m[0]*m[6]  *m[13] + m[4]*m[1]*m[14] - m[4]*m[2]*m[13] - m[12]*m[1]*m[6]  + m[12]*m[2]*m[5];
        inv[3]  = -m[1]*m[6]  *m[11] + m[1]*m[7]  *m[10] + m[5]*m[2]*m[11] - m[5]*m[3]*m[10] - m[9]  *m[2]*m[7]  + m[9]  *m[3]*m[6];
        inv[7]  =  m[0]*m[6]  *m[11] - m[0]*m[7]  *m[10] - m[4]*m[2]*m[11] + m[4]*m[3]*m[10] + m[8]  *m[2]*m[7]  - m[8]  *m[3]*m[6];
        inv[11] = -m[0]*m[5]  *m[11] + m[0]*m[7]  *m[9]  + m[4]*m[1]*m[11] - m[4]*m[3]*m[9]  - m[8]  *m[1]*m[7]  + m[8]  *m[3]*m[5];
        inv[15] =  m[0]*m[5]  *m[10] - m[0]*m[6]  *m[9]  - m[4]*m[1]*m[10] + m[4]*m[2]*m[9]  + m[8]  *m[1]*m[6]  - m[8]  *m[2]*m[5];

        float det = m[0]*inv[0] + m[1]*inv[4] + m[2]*inv[8] + m[3]*inv[12];
        if (Math.abs(det) < 1e-20f) return null;
        det = 1f / det;
        for (int i = 0; i < 16; i++) inv[i] *= det;
        return new Mat4().set(inv);
    }

    // ------------------------------------------------------------ vector utils

    public static float[] sub(float[] a, float[] b) { return new float[] { a[0]-b[0], a[1]-b[1], a[2]-b[2] }; }
    public static float[] cross(float[] a, float[] b) {
        return new float[] { a[1]*b[2]-a[2]*b[1], a[2]*b[0]-a[0]*b[2], a[0]*b[1]-a[1]*b[0] };
    }
    public static float dot(float[] a, float[] b) { return a[0]*b[0] + a[1]*b[1] + a[2]*b[2]; }
    public static float[] norm(float[] a) {
        float l = (float) Math.sqrt(dot(a, a));
        return l < 1e-12f ? new float[] { Float.NaN, Float.NaN, Float.NaN }
                         : new float[] { a[0]/l, a[1]/l, a[2]/l };
    }
    public static float length(float[] a) { return (float) Math.sqrt(dot(a, a)); }

    public static float[] fromArray(java.util.List<Float> l) {
        float[] r = new float[l.size()];
        for (int i = 0; i < r.length; i++) r[i] = l.get(i);
        return r;
    }

    @Override public String toString() { return Arrays.toString(m); }
}
