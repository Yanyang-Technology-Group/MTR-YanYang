// SPDX-License-Identifier: MIT
//
// color_probe.cpp — isolate the ARGB packing arithmetic.
//
// MTR's ObjModelLoader builds a material colour with
//     mergeColor(r, g, b, a) = r << 24 | g << 16 | b << 8 | a
// where each channel is `(int) (value * 255.0F)`. That pack performs a signed
// left shift into the sign bit for any red value above 127, which is undefined
// behaviour in C++ before C++20 and is a legitimate suspect when the packed
// value does not match the JVM's result.
//
// This probe prints every intermediate so the packing can be checked in
// isolation from the rest of the pipeline.

#include <cstdint>
#include <cstdio>

namespace {

/// The exact expression MTR uses, kept verbatim on purpose.
int32_t packSignedShift(int32_t r, int32_t g, int32_t b, int32_t a) {
    return (r << 24) | (g << 16) | (b << 8) | a;
}

/// The same value built without any shift that can overflow a signed int.
int32_t packUnsignedShift(int32_t r, int32_t g, int32_t b, int32_t a) {
    const uint32_t packed = (static_cast<uint32_t>(r) << 24)
            | (static_cast<uint32_t>(g) << 16)
            | (static_cast<uint32_t>(b) << 8)
            | static_cast<uint32_t>(a);
    return static_cast<int32_t>(packed);
}

void probe(const char* label, float kdR, float kdG, float kdB, float dissolve) {
    const int32_t c0 = static_cast<int32_t>(kdR * 255.0F);
    const int32_t c1 = static_cast<int32_t>(kdG * 255.0F);
    const int32_t c2 = static_cast<int32_t>(kdB * 255.0F);
    const int32_t c3 = static_cast<int32_t>(dissolve * 255.0F);
    const int32_t signedResult = packSignedShift(c0, c1, c2, c3);
    const int32_t unsignedResult = packUnsignedShift(c0, c1, c2, c3);

    std::printf("%-8s kd=(%g %g %g) d=%g -> c=(%d %d %d %d)\n",
            label, static_cast<double>(kdR), static_cast<double>(kdG),
            static_cast<double>(kdB), static_cast<double>(dissolve), c0, c1, c2, c3);
    std::printf("         signed   r<<24 = 0x%08X  packed = 0x%08X\n",
            static_cast<unsigned>(c0 << 24), static_cast<unsigned>(signedResult));
    std::printf("         unsigned r<<24 = 0x%08X  packed = 0x%08X\n",
            static_cast<unsigned>(static_cast<uint32_t>(c0) << 24),
            static_cast<unsigned>(unsignedResult));
    std::printf("         agree = %s\n", signedResult == unsignedResult ? "yes" : "NO");
}

} // namespace

int main() {
    probe("red", 1.0F, 0.0F, 0.0F, 1.0F);
    probe("blue", 0.0F, 0.0F, 1.0F, 0.5F);
    probe("mid", 0.5F, 0.25F, 0.75F, 1.0F);
    probe("low", 0.1F, 0.2F, 0.3F, 0.0F);
    return 0;
}
