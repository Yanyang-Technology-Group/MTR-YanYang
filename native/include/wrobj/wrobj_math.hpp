// SPDX-License-Identifier: MIT
//
// wrobj_math.hpp — single-precision math primitives that reproduce the exact
// observable results of the Java implementation this library replaces.
//
// The reference implementation composes three libraries:
//   * org.mtr.mapping.render.model.RawMesh  (transform loops, normals, distinct)
//   * org.joml.Vector3f / Matrix4f          (the arithmetic itself)
//
// Floating point results must match bit for bit, because the vertex data ends
// up in a GPU buffer and any deviation shows up as visible geometry differences.
// Two JOML details therefore matter and are reproduced deliberately:
//
//   1. Vector3f.rotateAxis() performs its arithmetic in double precision and
//      narrows to float only when storing the result.
//   2. Vector3f.normalize() scales by (float)(1.0 / Math.sqrt(lengthSquared())),
//      i.e. a reciprocal then a multiply, not three divisions. The rounding
//      differs from x/len in the last bit.
//
// Keep every expression below in the same association order as the original.

#pragma once

#include <cmath>
#include <cstdint>

namespace wrobj {


/// JOML's Math.cosFromSin, in the internal form.
///
/// Options.FASTMATH is off in this JOML build, so this is sqrt(1 - sin*sin) with the quadrant
/// chosen by reducing angle + PI/2 modulo 2*PI, not a call to sin(angle + PI/2).
///
/// Java's Math.PI is spelled out rather than taken from M_PI, which is a POSIX extension that
/// <cmath> exposes on some platforms and not others. It compiled on this platform until an
/// unrelated include changed and then did not.

/// JOML's own degrees-to-radians factor, as used by Math.toRadians.
///
/// Spelled the same way JOML spells it rather than as a truncated literal, because the value
/// has to agree bit for bit: Math.toRadians(angle) is angle / 180.0 * PI in double, and a
/// rounding difference here would show up as a last-bit difference in every rotated vertex.
inline constexpr double kDegreesToRadians = 0.017453292519943295;

struct Vec3f {
    float x = 0.0F;
    float y = 0.0F;
    float z = 0.0F;

    Vec3f() = default;
    Vec3f(float xIn, float yIn, float zIn) : x(xIn), y(yIn), z(zIn) {}

    bool isZero() const { return x == 0.0F && y == 0.0F && z == 0.0F; }

    bool operator==(const Vec3f& other) const { return x == other.x && y == other.y && z == other.z; }
    bool operator!=(const Vec3f& other) const { return !(*this == other); }
};

inline float jomlCosFromSin(float sin, float angle) {
    const float cos = std::sqrt(1.0F - sin * sin);
    const float pi = 3.14159265358979323846F;
    const float twoPi = pi * 2.0F;
    const float a = angle + pi / 2.0F;
    float b = a - static_cast<float>(static_cast<int>(a / twoPi)) * twoPi;
    if (b < 0.0F) {
        b += twoPi;
    }
    return b >= pi ? -cos : cos;
}

inline void jomlRotateX(Vec3f& v, float angle) {
    const float sin = std::sin(angle);
    const float cos = jomlCosFromSin(sin, angle);
    const float y = v.y * cos - v.z * sin;
    const float z = v.y * sin + v.z * cos;
    v.y = y;
    v.z = z;
}

inline void jomlRotateY(Vec3f& v, float angle) {
    const float sin = std::sin(angle);
    const float cos = jomlCosFromSin(sin, angle);
    const float x = v.x * cos + v.z * sin;
    const float z = -v.x * sin + v.z * cos;
    v.x = x;
    v.z = z;
}

inline void jomlRotateZ(Vec3f& v, float angle) {
    const float sin = std::sin(angle);
    const float cos = jomlCosFromSin(sin, angle);
    const float x = v.x * cos - v.y * sin;
    const float y = v.x * sin + v.y * cos;
    v.x = x;
    v.y = y;
}



/// Row-major 4x4, matching org.joml.Matrix4f's memory layout (m<row><column>).
struct Mat4f {
    float m[16] = {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};

    static Mat4f identity() { return Mat4f(); }

    float at(int row, int column) const { return m[row * 4 + column]; }
};

/// JOML Vector3f.rotateAxis(angle, axisX, axisY, axisZ).
///
/// JOML does NOT run the Rodrigues formula for every axis. It checks first whether the axis is a
/// unit cardinal vector and, if so, delegates to rotateX/rotateY/rotateZ, each of which uses a
/// half-angle formulation:
///
///   float hangle = angle * 0.5F;
///   float sinAngle = Math.sin(hangle);
///   float cosAngle = Math.cosFromSin(sinAngle, hangle);
///   float m00 = cosAngle + cosAngle;
///   float m01 = y * sinAngle + y * sinAngle;
///   ...
///
/// and multiplies those constructed matrix entries. That is mathematically the same rotation as
/// the Rodrigues form but not bit-identical, and the difference is large enough to change how
/// many vertices survive RawMesh.distinct(): the equivalence harness kept 24 vertices on the Java
/// side against 16 here, with the buffers diverging from the very first vertex, for a single 45
/// degree Y rotation. The general path is used only for a non-cardinal axis, which is why every
/// case without a rotation passed and the ones with one did not.
inline void rotateAxis(Vec3f& v, float angle, float axisX, float axisY, float axisZ) {
    // The cardinal fast paths, in JOML's order and with JOML's absEqualsOne test.
    if (axisY == 0.0F && axisZ == 0.0F && (axisX == 1.0F || axisX == -1.0F)) {
        jomlRotateX(v, axisX * angle);
        return;
    }
    if (axisX == 0.0F && axisZ == 0.0F && (axisY == 1.0F || axisY == -1.0F)) {
        jomlRotateY(v, axisY * angle);
        return;
    }
    if (axisX == 0.0F && axisY == 0.0F && (axisZ == 1.0F || axisZ == -1.0F)) {
        jomlRotateZ(v, axisZ * angle);
        return;
    }

    const double x = static_cast<double>(v.x);
    const double y = static_cast<double>(v.y);
    const double z = static_cast<double>(v.z);
    const double ax = static_cast<double>(axisX);
    const double ay = static_cast<double>(axisY);
    const double az = static_cast<double>(axisZ);

    const float csF = static_cast<float>(std::cos(static_cast<double>(angle)));
    const float snF = static_cast<float>(std::sin(static_cast<double>(angle)));
    const double cs = static_cast<double>(csF);
    const double sn = static_cast<double>(snF);
    const double omc = 1.0 - cs;

    v.x = static_cast<float>(
            x * (cs + ax * ax * omc) + y * (ax * ay * omc - sn * az) + z * (ax * az * omc + sn * ay));
    v.y = static_cast<float>(
            x * (ax * ay * omc + sn * az) + y * (cs + ay * ay * omc) + z * (ay * az * omc - sn * ax));
    v.z = static_cast<float>(
            x * (ax * az * omc - sn * ay) + y * (ay * az * omc + sn * ax) + z * (cs + az * az * omc));
}

/// JOML Matrix4f.transformPosition(Vector3f): row-major multiply against the
/// column vector (x, y, z, 1), ignoring the perspective divide.
inline Vec3f transformPosition(const Mat4f& matrix, const Vec3f& src) {
    const double x = static_cast<double>(src.x);
    const double y = static_cast<double>(src.y);
    const double z = static_cast<double>(src.z);
    const double rx = static_cast<double>(matrix.at(0, 0)) * x
            + static_cast<double>(matrix.at(0, 1)) * y
            + static_cast<double>(matrix.at(0, 2)) * z
            + static_cast<double>(matrix.at(0, 3));
    const double ry = static_cast<double>(matrix.at(1, 0)) * x
            + static_cast<double>(matrix.at(1, 1)) * y
            + static_cast<double>(matrix.at(1, 2)) * z
            + static_cast<double>(matrix.at(1, 3));
    const double rz = static_cast<double>(matrix.at(2, 0)) * x
            + static_cast<double>(matrix.at(2, 1)) * y
            + static_cast<double>(matrix.at(2, 2)) * z
            + static_cast<double>(matrix.at(2, 3));
    return Vec3f(static_cast<float>(rx), static_cast<float>(ry), static_cast<float>(rz));
}

/// JOML Vector3f.normalize(): scale by the float reciprocal of the length.
/// Returns the vector so it composes like the Java method does.
inline Vec3f& normalize(Vec3f& v) {
    const float len2 = v.x * v.x + v.y * v.y + v.z * v.z;
    if (len2 == 0.0F || len2 == 1.0F) {
        return v;
    }
    const float scalar = 1.0F / std::sqrt(len2);
    v.x *= scalar;
    v.y *= scalar;
    v.z *= scalar;
    return v;
}

/// JOML Matrix4f.transformDirection(Vector3f): no translation column, then
/// normalized by 1/sqrt(len2) exactly like JOML's normalize().
inline Vec3f transformDirection(const Mat4f& matrix, const Vec3f& src) {
    const double x = static_cast<double>(src.x);
    const double y = static_cast<double>(src.y);
    const double z = static_cast<double>(src.z);
    const float rx = static_cast<float>(
            static_cast<double>(matrix.at(0, 0)) * x
            + static_cast<double>(matrix.at(0, 1)) * y
            + static_cast<double>(matrix.at(0, 2)) * z);
    const float ry = static_cast<float>(
            static_cast<double>(matrix.at(1, 0)) * x
            + static_cast<double>(matrix.at(1, 1)) * y
            + static_cast<double>(matrix.at(1, 2)) * z);
    const float rz = static_cast<float>(
            static_cast<double>(matrix.at(2, 0)) * x
            + static_cast<double>(matrix.at(2, 1)) * y
            + static_cast<double>(matrix.at(2, 2)) * z);
    Vec3f result(rx, ry, rz);
    normalize(result);
    return result;
}

/// JOML Vector3f.mul(float) — component wise.
inline void mul(Vec3f& v, float sx, float sy, float sz) {
    v.x *= sx;
    v.y *= sy;
    v.z *= sz;
}

/// JOML Vector3f.add(float, float, float).
inline void add(Vec3f& v, float ax, float ay, float az) {
    v.x += ax;
    v.y += ay;
    v.z += az;
}

/// JOML Vector3f.add(Vector3fc).
inline void add(Vec3f& v, const Vec3f& other) {
    v.x += other.x;
    v.y += other.y;
    v.z += other.z;
}

/// Java's (byte) cast of a float: truncation toward zero, then wrap.
/// The reference packs normals as (byte) (n * 127.0F).
inline int8_t floatToByte(float value) {
    return static_cast<int8_t>(static_cast<int32_t>(value));
}

} // namespace wrobj
