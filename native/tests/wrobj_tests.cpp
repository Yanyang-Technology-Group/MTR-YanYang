// SPDX-License-Identifier: MIT
//
// wrobj_tests.cpp — unit tests for the native OBJ pipeline plus the golden
// equivalence harness.
//
// Two layers of verification:
//
//   1. Unit tests over hand-written OBJ documents. These cover the parser
//      behaviours that are easy to get subtly wrong: ASCII decoding, optional
//      v/vt/vn components, negative and relative indices, face index syntax,
//      triangulation fans, de-duplication scope, and normal generation.
//
//   2. Golden equivalence. Every case under the golden directory is rebuilt
//      natively and compared byte for byte against the buffers the Java
//      reference implementation produced. Run tools/generate-golden.ps1 first.
//
// Usage:
//   wrobj_tests [goldenDir]
//
// With no directory argument the golden layer is skipped and only unit tests
// run, so the suite passes on a clean checkout.

#include "golden_format.hpp"
#include "wrobj/wrobj.hpp"
#include "wrobj/wrobj_model.hpp"

#include <algorithm>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <fstream>
#include <iostream>
#include <map>
#include <sstream>
#include <string>
#include <vector>

#ifdef _WIN32
// NOMINMAX and WIN32_LEAN_AND_MEAN are supplied by the CMake target; guard the
// defines so the include does not redefine them.
#ifndef NOMINMAX
#define NOMINMAX
#endif
#ifndef WIN32_LEAN_AND_MEAN
#define WIN32_LEAN_AND_MEAN
#endif
#include <windows.h>
#else
#include <dirent.h>
#endif

namespace {

int gFailures = 0;
int gChecks = 0;

void check(bool condition, const std::string& what) {
    gChecks++;
    if (!condition) {
        gFailures++;
        std::cout << "  FAIL: " << what << "\n";
    }
}

template <typename T>
void checkEqual(const T& actual, const T& expected, const std::string& what) {
    gChecks++;
    if (!(actual == expected)) {
        gFailures++;
        std::cout << "  FAIL: " << what << " (expected " << expected << ", got " << actual << ")\n";
    }
}

void checkFloat(float actual, float expected, const std::string& what) {
    gChecks++;
    if (actual != expected) {
        gFailures++;
        std::cout << "  FAIL: " << what << " (expected " << expected << ", got " << actual << ")\n";
    }
}

std::string readFile(const std::string& path) {
    std::ifstream stream(path, std::ios::binary);
    if (!stream) {
        return std::string();
    }
    std::ostringstream buffer;
    buffer << stream.rdbuf();
    return buffer.str();
}

/// Bit pattern of a float, so equality tests can catch -0.0F and NaN payloads.
uint32_t floatBits(float value) {
    uint32_t bits = 0;
    std::memcpy(&bits, &value, sizeof(bits));
    return bits;
}

// ---------------------------------------------------------------------------
// Parser unit tests
// ---------------------------------------------------------------------------

void testParseComponents() {
    std::cout << "parse: v/vt/vn component handling\n";

    const std::string obj =
            "v 1 2 3\n"
            "v 4 5\n"
            "v 6 7 8 9\n"
            "vt 0.25\n"
            "vt 0.5 0.75 0.9\n"
            "vn 0 1 0\n"
            "f 1/1/1 2/2/1 3/1/1\n";

    wrobj::ObjDocument document;
    check(wrobj::parseObj(obj, document), "document parses");
    checkEqual(document.vertices.size(), size_t(3), "three vertices");
    // FloatTuple with two components leaves the third at its 0.0 default.
    checkFloat(document.vertices[1].z, 0.0F, "v with two components gets z = 0");
    // A four-component v drops w, which this model does not carry.
    checkFloat(document.vertices[2].z, 8.0F, "v with four components keeps z");
    checkEqual(document.texCoords.size(), size_t(2), "two texcoords");
    checkFloat(document.texCoords[0].y, 0.0F, "vt with one component gets v = 0");
    checkFloat(document.texCoords[1].z, 0.9F, "vt keeps its third component");

    checkEqual(document.faces.size(), size_t(1), "one face");
    checkEqual(document.faces[0].v.size(), size_t(3), "face has three corners");
    checkEqual(document.faces[0].v[2], int32_t(2), "absolute vertex index 3 becomes 2");
    checkEqual(document.faces[0].vt[1], int32_t(1), "texcoord index");
    checkEqual(document.faces[0].vn[0], int32_t(0), "normal index");
}

void testNegativeIndices() {
    std::cout << "parse: negative (relative) indices\n";

    const std::string obj =
            "v 0 0 0\n"
            "v 1 0 0\n"
            "v 0 1 0\n"
            "f -3 -2 -1\n";

    wrobj::ObjDocument document;
    check(wrobj::parseObj(obj, document), "document parses");
    checkEqual(document.faces.size(), size_t(1), "one face");
    checkEqual(document.faces[0].v[0], int32_t(0), "-3 resolves to 0");
    checkEqual(document.faces[0].v[1], int32_t(1), "-2 resolves to 1");
    checkEqual(document.faces[0].v[2], int32_t(2), "-1 resolves to 2");
}

void testIndexValidation() {
    std::cout << "parse: out-of-range indices are rejected\n";

    // DefaultObj.checkIndices throws for an index at or beyond the vertex count,
    // and ObjModelLoader's outer catch turns that into "model failed to load".
    const std::string obj =
            "v 0 0 0\n"
            "v 1 0 0\n"
            "f 1 2 3\n";

    wrobj::ObjDocument document;
    check(!wrobj::parseObj(obj, document), "index 3 with two vertices is rejected");
}

void testAsciiDecoding() {
    std::cout << "parse: US-ASCII decoding\n";

    // ObjReader uses StandardCharsets.US_ASCII, so byte 0xE4 becomes U+FFFD.
    // Group names produced by non-English editors hit this path.
    std::string obj = "g \xE4\xB8\xAD\nv 0 0 0\nv 1 0 0\nv 0 1 0\nf 1 2 3\n";

    wrobj::ObjDocument document;
    check(wrobj::parseObj(obj, document), "document with high bytes parses");
    checkEqual(document.groups.size(), size_t(2), "default group plus one named group");
    // Three replacement characters, one per high byte.
    checkEqual(document.groups[1].name, std::string("\xEF\xBF\xBD\xEF\xBF\xBD\xEF\xBF\xBD"),
            "high bytes become U+FFFD");
}

void testGroupKeywordState() {
    std::cout << "parse: g/o keyword state machine\n";

    // ObjReader tracks one "groupOrObject" keyword; a different keyword is
    // ignored until it appears again. The first `o` therefore loses to the
    // already-seen `g`.
    const std::string obj =
            "g alpha\n"
            "v 0 0 0\nv 1 0 0\nv 0 1 0\n"
            "f 1 2 3\n"
            "o beta\n"
            "f 1 2 3\n"
            "g gamma\n"
            "f 1 2 3\n";

    wrobj::ObjDocument document;
    check(wrobj::parseObj(obj, document), "document parses");
    // "default", "alpha", "gamma" — never "beta".
    checkEqual(document.groups.size(), size_t(3), "three groups");
    checkEqual(document.groups[1].name, std::string("alpha"), "second group is alpha");
    checkEqual(document.groups[2].name, std::string("gamma"), "third group is gamma");
}

void testMaterialGroups() {
    std::cout << "parse: material group assignment\n";

    const std::string obj =
            "v 0 0 0\nv 1 0 0\nv 0 1 0\n"
            "usemtl first\n"
            "f 1 2 3\n"
            "usemtl second\n"
            "f 1 2 3\n";

    wrobj::ObjDocument document;
    check(wrobj::parseObj(obj, document), "document parses");
    // Faces before the first usemtl belong to no material group at all, so only
    // two material groups exist.
    checkEqual(document.materialGroups.size(), size_t(3), "default plus two named");
    checkEqual(document.materialGroups[1].name, std::string("first"), "first material group");
    checkEqual(document.materialGroups[1].faceIndices.size(), size_t(1), "one face in first");
    checkEqual(document.materialGroups[2].name, std::string("second"), "second material group");
    checkEqual(document.faces[0].materialKey, std::string("first"), "face carries its key");
}

void testTriangulationFan() {
    std::cout << "convertToRenderable: triangulation fan\n";

    // Faces with more than three corners fan from vertex 0 using javagl's
    // (0, i-1, i) pattern, which for a quad yields (0,1,2) and (0,2,3).
    const std::string obj =
            "v 0 0 0\nv 1 0 0\nv 1 1 0\nv 0 1 0\n"
            "f 1 2 3 4\n";

    wrobj::ObjDocument document;
    check(wrobj::parseObj(obj, document), "document parses");
    wrobj::ObjFragment fragment;
    wrobj::groupToFragment(document, document.groups[0], fragment);
    wrobj::ObjFragment renderable;
    wrobj::convertToRenderable(fragment, renderable);

    checkEqual(renderable.faces.size(), size_t(2), "quad becomes two triangles");
    checkEqual(renderable.faces[0].v[0], int32_t(0), "first triangle starts at 0");
    checkEqual(renderable.faces[0].v[1], int32_t(1), "first triangle second index");
    checkEqual(renderable.faces[0].v[2], int32_t(2), "first triangle third index");
    checkEqual(renderable.faces[1].v[0], int32_t(0), "second triangle starts at 0");
    checkEqual(renderable.faces[1].v[1], int32_t(2), "second triangle second index");
    checkEqual(renderable.faces[1].v[2], int32_t(3), "second triangle third index");
}

/**
 * Pins the exact-comparison semantics that decide the vertex count.
 *
 * <p>The equivalence harness found model-scale-negative keeping 12 vertices on the Java side
 * against 8 natively, with the two sides agreeing on every triangle. The vertices that
 * separated them shared position and UV and a normal of z = -1, differing only in the last bit
 * of the normal's x and y - one side computed 0x...277 and the other 0x...278 for sin(pi)
 * residue left by a 180 degree rotation. Java compares vertices exactly and keeps both; a port
 * that treated them as equal would collapse them.</p>
 *
 * <p>So this test asserts both directions of that rule: a difference in the last bit of a
 * normal keeps vertices apart, and an exact duplicate still collapses. The second half matters
 * as much as the first, because widening equality until nothing collapses would pass the first
 * assertion while breaking deduplication.</p>
 */
void testDistinctKeepsLastBitNormalDifference() {
    std::cout << "distinct: a last-bit normal difference keeps vertices apart\n";

    wrobj::RawMesh mesh;
    wrobj::Vertex a;
    a.position = wrobj::Vec3f(0.0F, 0.0F, 0.0F);
    a.normal = wrobj::Vec3f(-8.742277E-8F, -8.742276E-8F, -1.0F);
    a.u = 0.0F;
    a.v = 0.0F;

    wrobj::Vertex b = a;
    // The neighbouring float, i.e. one ULP away, and the two must not compare equal.
    b.normal = wrobj::Vec3f(-8.742278E-8F, -8.742278E-8F, -1.0F);
    check(floatBits(a.normal.x) != floatBits(b.normal.x),
            "the two normals really are different floats");

    wrobj::Vertex exactCopy = a;
    mesh.vertices = {a, b, exactCopy};
    mesh.faces.push_back(wrobj::Face{{0, 1, 2}});
    mesh.distinct();

    checkEqual(mesh.vertices.size(), size_t(2), "the last-bit variant survives, the exact copy does not");
    checkEqual(mesh.faces[0].vertices[0], int32_t(0), "first index unchanged");
    checkEqual(mesh.faces[0].vertices[1], int32_t(1), "the variant keeps its own index");
    checkEqual(mesh.faces[0].vertices[2], int32_t(0), "the exact copy maps back to the first");
}

void testDistinctScope() {
    std::cout << "distinct: position, normal and UV participate; colour does not\n";

    wrobj::RawMesh mesh;
    wrobj::Vertex a;
    a.position = wrobj::Vec3f(0, 0, 0);
    a.normal = wrobj::Vec3f(0, 1, 0);
    a.u = 0.0F;
    a.v = 0.0F;
    a.color = 0x11223344;
    wrobj::Vertex b = a;
    b.color = 0x55667788; // colour is excluded from equals()
    wrobj::Vertex c = a;
    c.u = 1.0F; // UV differs, so this is a distinct vertex

    mesh.vertices = {a, b, c};
    mesh.faces.push_back(wrobj::Face{{0, 1, 2}});
    mesh.distinct();

    checkEqual(mesh.vertices.size(), size_t(2), "colour-only difference collapses");
    checkEqual(mesh.faces.size(), size_t(1), "face survives");
    checkEqual(mesh.faces[0].vertices[0], int32_t(0), "first index");
    checkEqual(mesh.faces[0].vertices[1], int32_t(0), "second index maps to the same vertex");
    checkEqual(mesh.faces[0].vertices[2], int32_t(1), "third index maps to the UV variant");
}

void testDuplicateFacesCollapse() {
    std::cout << "distinct: duplicate triangles collapse\n";

    wrobj::RawMesh mesh;
    for (int i = 0; i < 3; i++) {
        wrobj::Vertex vertex;
        vertex.position = wrobj::Vec3f(static_cast<float>(i), 0.0F, 0.0F);
        mesh.vertices.push_back(vertex);
    }
    mesh.faces.push_back(wrobj::Face{{0, 1, 2}});
    mesh.faces.push_back(wrobj::Face{{0, 1, 2}});
    mesh.faces.push_back(wrobj::Face{{1, 2, 0}});

    mesh.distinct();
    checkEqual(mesh.faces.size(), size_t(2), "repeated triangle collapses, reversed does not");
}

void testGenerateNormals() {
    std::cout << "generateNormals: face normals for zero normals\n";

    wrobj::RawMesh mesh;
    mesh.vertices.push_back(wrobj::Vertex{wrobj::Vec3f(0, 0, 0), wrobj::Vec3f(), 0, 0, 0, 0});
    mesh.vertices.push_back(wrobj::Vertex{wrobj::Vec3f(1, 0, 0), wrobj::Vec3f(), 0, 0, 0, 0});
    mesh.vertices.push_back(wrobj::Vertex{wrobj::Vec3f(0, 1, 0), wrobj::Vec3f(), 0, 0, 0, 0});
    mesh.faces.push_back(wrobj::Face{{0, 1, 2}});
    mesh.generateNormals();

    checkEqual(mesh.vertices.size(), size_t(3), "one copy per corner");
    // (1,0,0)-(0,0,0) x (0,1,0)-(0,0,0) = (0,0,1) for a counter-clockwise triangle.
    checkFloat(mesh.vertices[0].normal.z, 1.0F, "normal z is +1");
    checkFloat(mesh.vertices[0].normal.x, 0.0F, "normal x is 0");
}

void testDegenerateNormalFallback() {
    std::cout << "generateNormals: degenerate triangle yields +Y\n";

    wrobj::RawMesh mesh;
    mesh.vertices.push_back(wrobj::Vertex{wrobj::Vec3f(0, 0, 0), wrobj::Vec3f(), 0, 0, 0, 0});
    mesh.vertices.push_back(wrobj::Vertex{wrobj::Vec3f(1, 0, 0), wrobj::Vec3f(), 0, 0, 0, 0});
    mesh.vertices.push_back(wrobj::Vertex{wrobj::Vec3f(2, 0, 0), wrobj::Vec3f(), 0, 0, 0, 0});
    mesh.faces.push_back(wrobj::Face{{0, 1, 2}});
    mesh.generateNormals();

    checkFloat(mesh.vertices[0].normal.y, 1.0F, "degenerate triangle gets +Y");
    checkFloat(mesh.vertices[0].normal.x, 0.0F, "and zero x");
}

void testExistingNormalPreserved() {
    std::cout << "generateNormals: non-zero normals are preserved\n";

    wrobj::RawMesh mesh;
    const wrobj::Vec3f authored(0.0F, 0.0F, -1.0F);
    mesh.vertices.push_back(wrobj::Vertex{wrobj::Vec3f(0, 0, 0), authored, 0, 0, 0, 0});
    mesh.vertices.push_back(wrobj::Vertex{wrobj::Vec3f(1, 0, 0), authored, 0, 0, 0, 0});
    mesh.vertices.push_back(wrobj::Vertex{wrobj::Vec3f(0, 1, 0), authored, 0, 0, 0, 0});
    mesh.faces.push_back(wrobj::Face{{0, 1, 2}});
    mesh.generateNormals();

    checkFloat(mesh.vertices[0].normal.z, -1.0F, "authored normal survives");
}

void testScaleNormalCorrection() {
    std::cout << "applyScale: inverse-transpose normal correction\n";

    wrobj::RawMesh mesh;
    wrobj::Vertex vertex;
    vertex.position = wrobj::Vec3f(1.0F, 1.0F, 1.0F);
    vertex.normal = wrobj::Vec3f(1.0F, 0.0F, 0.0F);
    mesh.vertices.push_back(vertex);
    mesh.applyScale(2.0F, 2.0F, 2.0F);

    checkFloat(mesh.vertices[0].position.x, 2.0F, "position scales");
    // A uniform scale leaves the normal direction unchanged.
    checkFloat(mesh.vertices[0].normal.x, 1.0F, "normal x after uniform scale");
}

void testMirrorFlipParity() {
    std::cout << "applyMirror: face winding flips on odd mirror count\n";

    wrobj::RawMesh mesh;
    for (int i = 0; i < 3; i++) {
        wrobj::Vertex vertex;
        vertex.position = wrobj::Vec3f(static_cast<float>(i), 0.0F, 0.0F);
        mesh.vertices.push_back(vertex);
    }
    mesh.faces.push_back(wrobj::Face{{0, 1, 2}});

    mesh.applyMirror(true, false, false, true, false, false);
    checkEqual(mesh.faces[0].vertices[0], int32_t(2), "single-axis mirror reverses winding");

    mesh.applyMirror(true, true, false, true, true, false);
    checkEqual(mesh.faces[0].vertices[2], int32_t(0), "second mirror reverses it back");
}

void testMaterialOptions() {
    std::cout << "splitMaterialOptions and legacyShaderType\n";

    const wrobj::MaterialOptions plain = wrobj::splitMaterialOptions("fabric");
    checkEqual(plain.materialGroupName, std::string("fabric"), "plain group name");
    checkEqual(plain.shaderTypeToken, std::string(), "no shader token");
    check(!plain.flipTextureV, "no flip");

    const wrobj::MaterialOptions typed = wrobj::splitMaterialOptions("body#interior,flipv=1");
    checkEqual(typed.materialGroupName, std::string("body"), "group name before #");
    checkEqual(typed.shaderTypeToken, std::string("interior"), "bare token is the shader type");
    check(typed.flipTextureV, "flipv=1 is honoured");

    checkEqual(wrobj::legacyShaderType("exteriortranslucent"), int32_t(WROBJ_SHADER_TRANSLUCENT),
            "exteriortranslucent");
    checkEqual(wrobj::legacyShaderType("light"), int32_t(WROBJ_SHADER_CUTOUT_GLOWING), "light");
    checkEqual(wrobj::legacyShaderType("interior"), int32_t(WROBJ_SHADER_CUTOUT_BRIGHT), "interior");
    checkEqual(wrobj::legacyShaderType("INTERIOR"), int32_t(WROBJ_SHADER_CUTOUT_BRIGHT),
            "mapping is case insensitive");
    checkEqual(wrobj::legacyShaderType(""), int32_t(WROBJ_SHADER_CUTOUT), "default is CUTOUT");
}

void testMtlParsing() {
    std::cout << "parseMtl: Kd, d, Tr and map_Kd\n";

    const std::string mtl =
            "# comment\n"
            "newmtl one\n"
            "Kd 0.5 0.25 1.0\n"
            "map_Kd textures/one.png\n"
            "\n"
            "newmtl two\n"
            "d 0.5\n"
            "map_Kd -blendu on two.png\n";

    std::vector<wrobj::Mtl> mtls;
    check(wrobj::parseMtl(mtl, mtls), "mtl parses");
    checkEqual(mtls.size(), size_t(2), "two materials");
    for (size_t i = 0; i < mtls.size(); i++) {
        std::printf("         mtl[%zu] = \"%s\" hasKd=%d kd=(%g %g %g) dissolve=%g mapKd=\"%s\"\n",
                i, mtls[i].name.c_str(), mtls[i].hasKd ? 1 : 0,
                static_cast<double>(mtls[i].kdR), static_cast<double>(mtls[i].kdG),
                static_cast<double>(mtls[i].kdB), static_cast<double>(mtls[i].dissolve),
                mtls[i].mapKd.c_str());
    }
    checkEqual(mtls[0].name, std::string("one"), "first name");
    check(mtls[0].hasKd, "first has Kd");
    checkFloat(mtls[0].kdR, 0.5F, "Kd r");
    checkEqual(mtls[0].mapKd, std::string("textures/one.png"), "map_Kd");
    // DefaultMtl.getD() returns 1.0 when unset.
    checkFloat(mtls[0].dissolve, 1.0F, "dissolve defaults to 1");
    checkFloat(mtls[1].dissolve, 0.5F, "explicit dissolve");
    // The -blendu option consumes "on", leaving "two.png" as the file name.
    checkEqual(mtls[1].mapKd, std::string("two.png"), "options are consumed");
}

void testTrInvertsDissolve() {
    std::cout << "parseMtl: Tr inverts into dissolve\n";

    const std::string mtl = "newmtl x\nTr 0.25\n";
    std::vector<wrobj::Mtl> mtls;
    check(wrobj::parseMtl(mtl, mtls), "mtl parses");
    checkFloat(mtls[0].dissolve, 0.75F, "Tr 0.25 becomes d 0.75");
}

/// MtlReader splices a line that ends with a backslash onto the following line, after
/// dropping two characters. This is inherited from the reference implementation and is easy
/// to mistake for a normalisation problem, so it gets its own test.
void testMtlLineSplicing() {
    std::cout << "parseMtl: a trailing backslash splices the next line\n";

    // `map_Kd a\` drops the backslash and the character before it, then appends the next
    // line separated by a space, so the file name ends up being "b.png".
    const std::string spliced = "newmtl m\nmap_Kd a\\\nb.png\n";
    std::vector<wrobj::Mtl> mtls;
    check(wrobj::parseMtl(spliced, mtls), "the spliced document parses");
    checkEqual(mtls.size(), size_t(1), "one material");
    if (!mtls.empty()) {
        checkEqual(mtls[0].mapKd, std::string("b.png"), "the splice joins the lines");
    }

    // Two characters are dropped, so the character before the backslash goes too, and the
    // joined line keeps the separator space. This mirrors the reference implementation rather
    // than tidying it up: matching the quirk is what keeps the two pipelines identical.
    const std::string dropTwo = "newmtl m\nmap_Kd ab\\\nc.png\n";
    mtls.clear();
    check(wrobj::parseMtl(dropTwo, mtls), "the dropped-character document parses");
    if (!mtls.empty()) {
        // The splice yields "map_Kd a c.png", and readTextureOptions assigns the file name for
        // *every* token that is not a recognised option, so the last one wins. A two-token
        // path is therefore impossible to express, which is the reference's behaviour and not
        // something to "fix".
        checkEqual(mtls[0].mapKd, std::string("c.png"),
                "the last bare token wins the file name");
    }

    // Options are consumed with their arguments, so a bare token after them is the file name.
    const std::string withOption = "newmtl m\nmap_Kd -blendu on real.png\n";
    mtls.clear();
    check(wrobj::parseMtl(withOption, mtls), "the option document parses");
    if (!mtls.empty()) {
        checkEqual(mtls[0].mapKd, std::string("real.png"),
                "-blendu consumes its argument, leaving the path intact");
    }

    // An unterminated splice leaves the field empty, which is exactly why a texture that
    // ends in a backslash cannot be expressed in an MTL.
    const std::string unterminated = "newmtl m\nmap_Kd dangling\\\n";
    mtls.clear();
    check(wrobj::parseMtl(unterminated, mtls), "the unterminated document parses");
    if (!mtls.empty()) {
        checkEqual(mtls[0].mapKd, std::string(), "an unterminated splice yields nothing");
    }
}

/// The texture string the native parser reports becomes the resolved path, and the resolved
/// path is part of the material identity that decides which meshes merge. Getting the
/// normalisation wrong therefore splits buckets rather than merely mislabelling a texture.
///
/// Every expectation below is the JVM's own output, printed by
/// native/tools/TexturePathReference.java.
void testTextureStringNormalisation() {
    std::cout << "keys: texture string normalisation matches the JVM\n";

    struct Case {
        const char* raw;
        const char* expected;
    };
    const Case cases[] = {
        {"textures\\body.png", "textures/body.png"},
        // The doubled backslash collapses to ONE slash, because the reference replaces the
        // two-character sequence with a single character before the second pass runs.
        {"textures\\\\body.png", "textures/body.png"},
        // Three backslashes: the first two collapse, the third becomes a slash of its own,
        // which is where the doubled slash comes from.
        {"textures\\\\\\body.png", "textures//body.png"},
        {"textures\\\\\\\\body.png", "textures//body.png"},
        {"a\\b\\c.png", "a/b/c.png"},
        {"a\\\\b\\\\c.png", "a/b/c.png"},
        {"plain.png", "plain.png"},
        {"dir/sub/plain.png", "dir/sub/plain.png"},
        // Upper case folds down; formatIdentifierString lower-cases the whole string.
        {"UPPER/Case.PNG", "upper/case.png"},
        {"\\leading.png", "/leading.png"},
        // A *trailing* backslash cannot appear in this table: MtlReader splices a line ending
        // in "\\" onto the next line, and trim() then strips any space that was appended, so
        // the value comes back empty however it is written. testMtlLineSplicing pins that
        // separately, because it is a parser rule rather than a normalisation one.
    };

    // The texture string only reaches the key table through a document, so each case runs a
    // one-material OBJ whose group name is the string under test. A group name cannot hold a
    // backslash in practice, so the MTL carries it instead: map_Kd is what feeds the resolver.
    for (const Case& testCase : cases) {
        const std::string obj =
                "mtllib t.mtl\nv 0 0 0\nv 1 0 0\nv 0 1 0\nusemtl mat\nf 1 2 3\n";
        const std::string mtl = std::string("newmtl mat\nKd 1 1 1\nmap_Kd ") + testCase.raw + "\n";

        WrObjKeyList* keys = wr_obj_keys_load(obj.data(), static_cast<int32_t>(obj.size()),
                mtl.data(), static_cast<int32_t>(mtl.size()), 0);
        if (keys == nullptr || keys->count < 1) {
            check(false, std::string("keys load for ") + testCase.raw);
            wr_obj_keys_free(keys);
            continue;
        }
        checkEqual(std::string(keys->entries[0].textureString), std::string(testCase.expected),
                std::string("normalisation of \"") + testCase.raw + "\"");
        wr_obj_keys_free(keys);
    }

    // A namespaced string keeps its colon, and the shader options on the group name are
    // stripped from the texture side of the key.
    //
    // This case carries a `usemtl`, because javagl creates no material group at all without
    // one: ObjSplitting.splitByMaterialGroups iterates obj.getNumMaterialGroups(), and
    // ObjReader never creates that group when no usemtl was seen. An OBJ with no usemtl has
    // no material groups, so ObjModelLoader.loadModel produces an empty mesh list - see
    // testNoUsemtlYieldsNoKeys, which pins that.
    {
        const std::string obj = "v 0 0 0\nv 1 0 0\nv 0 1 0\nusemtl _\nf 1 2 3\n";
        WrObjKeyList* keys = wr_obj_keys_load(obj.data(), static_cast<int32_t>(obj.size()),
                nullptr, 0, 0);
        check(keys != nullptr && keys->count == 1, "a document with no MTL yields one key");
        if (keys != nullptr && keys->count == 1) {
            // With no MTL the material group name doubles as the texture string, and "_"
            // means "no texture".
            checkEqual(std::string(keys->entries[0].textureString), std::string(),
                    "the underscore group means no texture");
        }
        wr_obj_keys_free(keys);
    }
}

// ---------------------------------------------------------------------------
// End-to-end API tests
// ---------------------------------------------------------------------------

/// Look up one key by name in the key list.
const WrObjKeyInfo* findKey(const WrObjKeyList* list, const std::string& name) {
    if (list == nullptr) {
        return nullptr;
    }
    for (int32_t i = 0; i < list->count; i++) {
        if (name == list->entries[i].key) {
            return &list->entries[i];
        }
    }
    return nullptr;
}

void testMaterialKeySurvivesPipeline() {
    std::cout << "parse: material key survives fragment and renderable stages\n";

    const std::string obj =
            "v 0 0 0\nv 1 0 0\nv 0 1 0\n"
            "usemtl body#interior\nf 1 2 3\n";

    wrobj::ObjDocument document;
    check(wrobj::parseObj(obj, document), "document parses");
    checkEqual(document.faces.size(), size_t(1), "one face parsed");
    if (!document.faces.empty()) {
        checkEqual(document.faces[0].materialKey, std::string("body#interior"),
                "material key after parsing");
    }
    checkEqual(document.materialGroups.size(), size_t(2), "default plus one named material group");

    wrobj::ObjFragment fragment;
    wrobj::groupToFragment(document, document.groups[0], fragment);
    checkEqual(fragment.faces.size(), size_t(1), "one face in the fragment");
    if (!fragment.faces.empty()) {
        checkEqual(fragment.faces[0].materialKey, std::string("body#interior"),
                "material key after groupToFragment");
    }

    wrobj::ObjFragment renderable;
    wrobj::convertToRenderable(fragment, renderable);
    checkEqual(renderable.faces.size(), size_t(1), "one face renderable");
    if (!renderable.faces.empty()) {
        checkEqual(renderable.faces[0].materialKey, std::string("body#interior"),
                "material key after convertToRenderable");
    }
}

void testKeyDiscovery() {
    std::cout << "api: wr_obj_keys_load key table\n";

    const std::string obj =
            "mtllib model.mtl\n"
            "v 0 0 0\nv 1 0 0\nv 0 1 0\n"
            "usemtl body#interior\n"
            "f 1 2 3\n"
            "usemtl glass#exteriortranslucent\n"
            "f 1 2 3\n";
    const std::string mtl =
            "newmtl body#interior\nKd 1 0 0\nmap_Kd body.png\n"
            "newmtl glass#exteriortranslucent\nKd 0 0 1\nd 0.5\nmap_Kd glass.png\n";

    WrObjKeyList* keys = wr_obj_keys_load(obj.data(), static_cast<int32_t>(obj.size()),
            mtl.data(), static_cast<int32_t>(mtl.size()), 0);
    check(keys != nullptr, "keys load");
    if (keys == nullptr) {
        return;
    }
    if (keys->count != 2) {
        std::cout << "         discovered " << keys->count << " key(s):";
        for (int32_t i = 0; i < keys->count; i++) {
            std::cout << " \"" << keys->entries[i].key << "\"";
        }
        std::cout << "\n";
    }
    checkEqual(keys->count, int32_t(2), "two keys");

    const WrObjKeyInfo* body = findKey(keys, "body#interior");
    check(body != nullptr, "body key present");
    if (body != nullptr) {
        checkEqual(body->shaderType, int32_t(WROBJ_SHADER_CUTOUT_BRIGHT), "body shader type");
        checkEqual(std::string(body->textureString), std::string("body.png"), "body texture");
        // Values below are the JVM's own output, produced by
        // tools/ColorReference.java with the exact expression ObjModelLoader uses.
        // mergeColor packs red into the most significant byte and alpha into the
        // least significant one, so "opaque red" is 0xFF0000FF, not 0xFFFF0000.
        checkEqual(body->color, static_cast<int32_t>(0xFF0000FF), "body colour");
    }

    const WrObjKeyInfo* glass = findKey(keys, "glass#exteriortranslucent");
    check(glass != nullptr, "glass key present");
    if (glass != nullptr) {
        checkEqual(glass->shaderType, int32_t(WROBJ_SHADER_TRANSLUCENT), "glass shader type");
        // Kd 0 0 1 with d 0.5: (int)(0.5F * 255.0F) truncates to 127.
        checkEqual(glass->color, static_cast<int32_t>(0x0000FF7F), "glass colour truncates like Java");
    }

    wr_obj_keys_free(keys);
}

void testColorPackingMatchesJvm() {
    std::cout << "api: material colours match the JVM reference values\n";

    // Every expectation here was produced by native/tools/ColorReference.java,
    // which evaluates the same expressions the mod does. Keeping the table in the
    // test means a future refactor of the packing fails loudly instead of
    // silently shifting the tint of every vehicle.
    struct Case {
        const char* label;
        const char* mtlBody;
        int32_t expected;
    };
    const Case cases[] = {
        {"Kd 1 0 0, d unset", "Kd 1 0 0\n", static_cast<int32_t>(0xFF0000FF)},
        {"Kd 0 0 1, d 0.5", "Kd 0 0 1\nd 0.5\n", static_cast<int32_t>(0x0000FF7F)},
        {"Kd 0.5 0.25 0.75", "Kd 0.5 0.25 0.75\n", static_cast<int32_t>(0x7F3FBFFF)},
        {"Kd 0.1 0.2 0.3, d 0", "Kd 0.1 0.2 0.3\nd 0\n", static_cast<int32_t>(0x19334C00)},
        {"Kd 0.999 0.5 0.001, d 0.999", "Kd 0.999 0.5 0.001\nd 0.999\n",
                static_cast<int32_t>(0xFE7F00FE)},
    };

    for (const Case& testCase : cases) {
        const std::string obj =
                "mtllib model.mtl\nv 0 0 0\nv 1 0 0\nv 0 1 0\nusemtl mat\nf 1 2 3\n";
        const std::string mtl = std::string("newmtl mat\n") + testCase.mtlBody;

        WrObjKeyList* keys = wr_obj_keys_load(obj.data(), static_cast<int32_t>(obj.size()),
                mtl.data(), static_cast<int32_t>(mtl.size()), 0);
        if (keys == nullptr || keys->count < 1) {
            check(false, std::string("keys load for ") + testCase.label);
            wr_obj_keys_free(keys);
            continue;
        }
        checkEqual(keys->entries[0].color, testCase.expected,
                std::string("colour for ") + testCase.label);
        wr_obj_keys_free(keys);
    }
}

void testEndToEndGeometry() {
    std::cout << "api: full build produces interleaved vertices and indices\n";

    const std::string obj =
            "v 0 0 0\nv 1 0 0\nv 0 1 0\n"
            "usemtl _\n"
            "f 1 2 3\n";

    WrObjKeyList* keys = wr_obj_keys_load(obj.data(), static_cast<int32_t>(obj.size()),
            nullptr, 0, 0);
    check(keys != nullptr, "keys load without an MTL");
    if (keys == nullptr) {
        return;
    }
    checkEqual(keys->count, int32_t(1), "one key");
    checkEqual(std::string(keys->entries[0].key), std::string("_"), "key is the group name");

    const int32_t handles[1] = {7};

    // The host reports whether each key's colour is authoritative. These cases use

    // materials with no Kd, so the colour is not forced.

    const int32_t colorSet[1] = {0};
    WrObjBuilder* builder = wr_obj_builder_create(obj.data(), static_cast<int32_t>(obj.size()),
            nullptr, 0, handles, colorSet, 1, 0, 0);
    check(builder != nullptr, "builder creates");
    if (builder == nullptr) {
        wr_obj_keys_free(keys);
        return;
    }

    wr_obj_builder_add_transform(builder, WROBJ_SHADER_CUTOUT, 0.0, 0.0, 0.0, 0);
    WrObjResult* result = wr_obj_builder_build(builder);
    check(result != nullptr, "build succeeds");
    if (result != nullptr) {
        checkEqual(result->drawCount, int32_t(1), "one draw bucket");
        checkEqual(result->vertexDataLength, int32_t(3 * WROBJ_VERTEX_STRIDE), "three vertices");
        checkEqual(result->indexDataLength, int32_t(3 * 4), "one triangle");
        checkEqual(result->totalFaceCount, int64_t(1), "one face");
        check(result->estimatedJavaHeapBytes > 0, "heap estimate is reported");

        const auto* draws = static_cast<const WrObjResultDraw*>(result->drawData);
        checkEqual(draws[0].textureHandle, int32_t(7), "host texture handle is carried through");
        checkEqual(draws[0].keyIndex, int32_t(0), "draw names its key");

        wr_obj_result_free(result);
    }
    wr_obj_builder_free(builder);
    wr_obj_keys_free(keys);
}

void testBuildIsDeterministic() {
    std::cout << "api: repeated builds are byte identical\n";

    const std::string obj =
            "v 0 0 0\nv 1 0 0\nv 0 1 0\nv 0 0 1\n"
            "usemtl a\nf 1 2 3\n"
            "usemtl b\nf 1 3 4\nf 2 4 3\n";

    std::vector<uint8_t> first;
    std::vector<uint8_t> second;
    for (int pass = 0; pass < 2; pass++) {
        const int32_t handles[2] = {0, 1};
        const int32_t colorSet[2] = {0, 0};
        WrObjBuilder* builder = wr_obj_builder_create(obj.data(), static_cast<int32_t>(obj.size()),
                nullptr, 0, handles, colorSet, 2, 0, 0);
        check(builder != nullptr, "builder creates");
        if (builder == nullptr) {
            return;
        }
        wr_obj_builder_add_transform(builder, WROBJ_SHADER_CUTOUT, 0.0, 0.0, 0.0, 0);
        wr_obj_builder_add_transform(builder, WROBJ_SHADER_CUTOUT, 1.0, 0.0, 0.0, 1);
        WrObjResult* result = wr_obj_builder_build(builder);
        check(result != nullptr, "build succeeds");
        if (result != nullptr) {
            const auto* bytes = static_cast<const uint8_t*>(result->vertexData);
            std::vector<uint8_t>& target = pass == 0 ? first : second;
            target.assign(bytes, bytes + result->vertexDataLength);
            wr_obj_result_free(result);
        }
        wr_obj_builder_free(builder);
    }
    checkEqual(first.size(), second.size(), "same buffer size");
    check(first == second, "same buffer contents");
}

/// wr_obj_builder_build is a read of the accumulated state, not a consuming step:
/// the doors-open and doors-closed models are built from one shared wrapper list,
/// and the per-position door models are built while placements are still being
/// added. Building twice must produce byte-identical output, and a placement
/// added between two builds must appear in the later build only.
void testBuildIsRepeatable() {
    std::cout << "api: a builder builds repeatably without consuming its state\n";

    const std::string obj = "v 0 0 0\nv 1 0 0\nv 0 1 0\nusemtl _\nf 1 2 3\n";
    const int32_t handles[1] = {0};
    const int32_t colorSet[1] = {0};

    WrObjBuilder* builder = wr_obj_builder_create(obj.data(), static_cast<int32_t>(obj.size()),
            nullptr, 0, handles, colorSet, 1, 0, 0);
    check(builder != nullptr, "builder creates");
    if (builder == nullptr) {
        return;
    }

    wr_obj_builder_add_transform(builder, WROBJ_SHADER_CUTOUT, 0.0, 0.0, 0.0, 0);

    std::vector<uint8_t> first;
    std::vector<uint32_t> firstIndices;
    WrObjResult* result = wr_obj_builder_build(builder);
    check(result != nullptr, "the first build succeeds");
    if (result != nullptr) {
        checkEqual(result->vertexDataLength, int32_t(3 * WROBJ_VERTEX_STRIDE), "the first build holds one placement");
        const auto* bytes = static_cast<const uint8_t*>(result->vertexData);
        first.assign(bytes, bytes + result->vertexDataLength);
        const auto* indices = static_cast<const uint32_t*>(result->indexData);
        firstIndices.assign(indices, indices + result->indexDataLength / 4);
        wr_obj_result_free(result);
    }

    result = wr_obj_builder_build(builder);
    check(result != nullptr, "the second build succeeds");
    if (result != nullptr) {
        checkEqual(result->vertexDataLength, int32_t(3 * WROBJ_VERTEX_STRIDE), "the second build holds the same placement");
        const auto* bytes = static_cast<const uint8_t*>(result->vertexData);
        check(std::vector<uint8_t>(bytes, bytes + result->vertexDataLength) == first,
                "the second build is byte-identical to the first");
        const auto* indices = static_cast<const uint32_t*>(result->indexData);
        check(std::vector<uint32_t>(indices, indices + result->indexDataLength / 4) == firstIndices,
                "the second build's indices are byte-identical");
        wr_obj_result_free(result);
    }

    wr_obj_builder_add_transform(builder, WROBJ_SHADER_CUTOUT, 5.0, 0.0, 0.0, 0);
    result = wr_obj_builder_build(builder);
    check(result != nullptr, "the build after a new placement succeeds");
    if (result != nullptr) {
        checkEqual(result->vertexDataLength, int32_t(6 * WROBJ_VERTEX_STRIDE), "the third build holds both placements");
        wr_obj_result_free(result);
    }

    wr_obj_builder_free(builder);
}

/**
 * Pins the rule that decided a real divergence: no `usemtl` means no material groups.
 *
 * <p>javagl's ObjSplitting.splitByMaterialGroups iterates obj.getNumMaterialGroups(), and
 * ObjReader never creates that group when the document carried no usemtl statement. So an
 * OBJ with no usemtl has no material groups, ObjModelLoader.loadModel produces an empty mesh
 * list, and the model renders as nothing. A previous version of the native code synthesised
 * one unnamed bucket for exactly this case, which made the native and Java paths disagree on
 * bucket count and on the packed vertex buffer.</p>
 *
 * <p>The assertion is on the count rather than on the absence of a crash because zero is the
 * whole point: one bucket and zero buckets are both "valid-looking" outputs, and only the
 * count distinguishes them.</p>
 */
void testNoUsemtlYieldsNoKeys() {
    std::cout << "keys: a document with no usemtl has no material groups\n";

    const std::string obj = "v 0 0 0\nv 1 0 0\nv 0 1 0\nf 1 2 3\n";

    WrObjKeyList* keys = wr_obj_keys_load(obj.data(), static_cast<int32_t>(obj.size()),
            nullptr, 0, 0);
    // Null rather than an empty list: the C API reports "no keys" by returning null, which is
    // why wr_obj_keys_load's contract has to be "null on failure or on an empty result" and
    // why the Java bridge turns null into a null array rather than into an empty one.
    check(keys == nullptr, "no usemtl produces no keys at all");
    wr_obj_keys_free(keys);

    // The builder has nothing to build, so it declines. Returning null is the honest answer:
    // a builder that produced an empty result would be indistinguishable from a model that
    // legitimately has no geometry.
    const int32_t handles[1] = {0};
    const int32_t colorSet[1] = {0};
    WrObjBuilder* builder = wr_obj_builder_create(obj.data(), static_cast<int32_t>(obj.size()),
            nullptr, 0, handles, colorSet, 1, 0, 0);
    check(builder == nullptr, "a document with no usemtl produces no builder");
    wr_obj_builder_free(builder);

    // The same document with a usemtl does produce both, which is what makes the assertion
    // above about the usemtl rather than about some other property of the input.
    const std::string withMaterial = "v 0 0 0\nv 1 0 0\nv 0 1 0\nusemtl _\nf 1 2 3\n";
    WrObjKeyList* withKeys = wr_obj_keys_load(withMaterial.data(),
            static_cast<int32_t>(withMaterial.size()), nullptr, 0, 0);
    check(withKeys != nullptr && withKeys->count == 1,
            "the same document with a usemtl produces one key");
    wr_obj_keys_free(withKeys);
}

void testSeparateBuildersDoNotShare() {
    std::cout << "api: separate builders stay isolated\n";

    // This is the invariant behind the YanYang cross-condition rendering fix: two
    // parts must not accumulate into one buffer.
    //
    // The `usemtl` is load-bearing: without one the document has no material groups, so the
    // builder has nothing to build and returns null. See testNoUsemtlYieldsNoKeys.
    const std::string obj = "v 0 0 0\nv 1 0 0\nv 0 1 0\nusemtl _\nf 1 2 3\n";
    const int32_t handles[1] = {0};
    const int32_t colorSet[1] = {0};

    WrObjBuilder* first = wr_obj_builder_create(obj.data(), static_cast<int32_t>(obj.size()),
            nullptr, 0, handles, colorSet, 1, 0, 0);
    WrObjBuilder* second = wr_obj_builder_create(obj.data(), static_cast<int32_t>(obj.size()),
            nullptr, 0, handles, colorSet, 1, 0, 0);
    check(first != nullptr && second != nullptr, "both builders create");
    if (first == nullptr || second == nullptr) {
        wr_obj_builder_free(first);
        wr_obj_builder_free(second);
        return;
    }

    wr_obj_builder_add_transform(first, WROBJ_SHADER_CUTOUT, 0.0, 0.0, 0.0, 0);
    wr_obj_builder_add_transform(second, WROBJ_SHADER_CUTOUT, 5.0, 0.0, 0.0, 0);

    WrObjResult* firstResult = wr_obj_builder_build(first);
    WrObjResult* secondResult = wr_obj_builder_build(second);
    check(firstResult != nullptr && secondResult != nullptr, "both build");
    if (firstResult != nullptr) {
        checkEqual(firstResult->vertexDataLength, int32_t(3 * WROBJ_VERTEX_STRIDE),
                "first builder holds one placement");
        wr_obj_result_free(firstResult);
    }
    if (secondResult != nullptr) {
        checkEqual(secondResult->vertexDataLength, int32_t(3 * WROBJ_VERTEX_STRIDE),
                "second builder holds one placement");
        wr_obj_result_free(secondResult);
    }
    wr_obj_builder_free(first);
    wr_obj_builder_free(second);
}

// ---------------------------------------------------------------------------
// Golden equivalence
// ---------------------------------------------------------------------------

/// Description of one golden case, parsed from a .case file next to the .golden.
struct GoldenCase {
    std::string name;
    std::string objPath;
    std::string mtlPath;
    std::string goldenPath;
    bool flipTextureV = false;
    bool materialGroupTextures = true;
};

std::map<std::string, std::string> readCaseFile(const std::string& path) {
    std::map<std::string, std::string> values;
    std::ifstream stream(path);
    std::string line;
    while (std::getline(stream, line)) {
        const size_t separator = line.find('=');
        if (separator == std::string::npos) {
            continue;
        }
        values[line.substr(0, separator)] = line.substr(separator + 1);
    }
    return values;
}

/// Applies one of the case file's vector transforms to a builder.
///
/// The case file writes them as `x,y,z` and the native operation codes are
/// 0 translation, 1 rotation in degrees, 2 scale, 3 mirror flags, which is the same
/// numbering wr_obj_builder_apply_* uses.
void applyCaseVector(
        WrObjBuilder* builder,
        const std::map<std::string, std::string>& values,
        const char* key,
        int32_t operation
) {
    const auto iterator = values.find(key);
    if (iterator == values.end()) {
        return;
    }
    double a = 0.0;
    double b = 0.0;
    double c = 0.0;
    if (std::sscanf(iterator->second.c_str(), "%lf,%lf,%lf", &a, &b, &c) != 3) {
        return;
    }
    wr_obj_builder_apply_transform(builder, operation, a, b, c);
}

int runGolden(const std::string& directory, const std::vector<std::string>& caseFiles) {
    std::cout << "\ngolden equivalence (" << caseFiles.size() << " case(s))\n";
    int failures = 0;

    for (const std::string& caseFile : caseFiles) {
        const std::map<std::string, std::string> values = readCaseFile(caseFile);
        const auto nameIt = values.find("name");
        const auto objIt = values.find("obj");
        const auto goldenIt = values.find("golden");
        if (nameIt == values.end() || objIt == values.end() || goldenIt == values.end()) {
            continue;
        }

        const std::string name = nameIt->second;
        const std::string objPath = directory + "/" + objIt->second;
        const std::string goldenPath = directory + "/" + goldenIt->second;
        const auto mtlIt = values.find("mtl");
        const std::string mtlPath = mtlIt == values.end() ? std::string() : directory + "/" + mtlIt->second;

        golden::File expected;
        std::string error;
        if (!golden::read(goldenPath, expected, error)) {
            std::cout << "  " << name << ": " << error << "\n";
            failures++;
            continue;
        }

        const std::string objContent = readFile(objPath);
        if (objContent.empty()) {
            std::cout << "  " << name << ": cannot read " << objPath << "\n";
            failures++;
            continue;
        }
        const std::string mtlContent = mtlPath.empty() ? std::string() : readFile(mtlPath);

        // The golden's key table supplies the texture handles the native side
        // would otherwise get from the host, so the comparison covers texture
        // identity as well as geometry.
        //
        // The golden writes 0xFFFFFFFF as a bucket colour when the material carried
        // no Kd, which is the same value a forced white would produce but a different
        // meaning: hasColor decides whether the colour is authoritative at all, and
        // the golden records the colour a Java-side build observed. Both are fed back
        // so the comparison stays end to end.
        std::vector<int32_t> handles(expected.keys.size(), -1);
        std::vector<int32_t> colorSet(expected.keys.size(), 0);
        for (size_t i = 0; i < expected.keys.size(); i++) {
            handles[i] = static_cast<int32_t>(i);
            colorSet[i] = expected.keys[i].color == static_cast<int32_t>(0xFFFFFFFF) ? 0 : 1;
        }
        // Each golden carries the placement list in its draw offsets; the case
        // file records how many placements were applied.
        const auto transformsIt = values.find("transforms");
        std::vector<std::string> transformList;
        if (transformsIt != values.end()) {
            std::stringstream stream(transformsIt->second);
            std::string token;
            while (std::getline(stream, token, ';')) {
                transformList.push_back(token);
            }
        }

        WrObjBuilder* builder = wr_obj_builder_create(
                objContent.data(), static_cast<int32_t>(objContent.size()),
                mtlContent.empty() ? nullptr : mtlContent.data(),
                static_cast<int32_t>(mtlContent.size()),
                handles.data(), colorSet.data(), static_cast<int32_t>(handles.size()),
                values.count("materialGroupTextures") > 0 ? 0 : 1,
                values.count("flipTextureV") > 0 ? 1 : 0);
        if (builder == nullptr) {
            std::cout << "  " << name << ": native builder rejected the document\n";
            failures++;
            continue;
        }

        // Model-level transform, i.e. ObjectResource.applyTranslation / applyRotation /
        // applyScale / applyMirror, which the reference applies to the ObjModel before the
        // vehicle-level transform and before any placement. Order matters: the rotation runs
        // X then Y then Z, and the scale follows the translation.
        applyCaseVector(builder, values, "modelTranslation", 0);
        applyCaseVector(builder, values, "modelRotation", 1);
        applyCaseVector(builder, values, "modelScale", 2);
        applyCaseVector(builder, values, "modelMirror", 3);

        // Vehicle-level transform, i.e. StoredModelResourceBase.transform.
        applyCaseVector(builder, values, "schemaTranslation", 0);
        applyCaseVector(builder, values, "schemaRotation", 1);
        applyCaseVector(builder, values, "schemaScale", 2);
        applyCaseVector(builder, values, "schemaMirror", 3);

        for (const std::string& transform : transformList) {
            // Format: shaderType,x,y,z,flipped
            int shaderType = 0;
            int flipped = 0;
            double x = 0.0;
            double y = 0.0;
            double z = 0.0;
            if (std::sscanf(transform.c_str(), "%d,%lf,%lf,%lf,%d", &shaderType, &x, &y, &z, &flipped) == 5) {
                wr_obj_builder_add_transform(builder, shaderType, x, y, z, flipped);
            }
        }

        WrObjResult* result = wr_obj_builder_build(builder);
        if (result == nullptr) {
            std::cout << "  " << name << ": native build produced no geometry\n";
            failures++;
            wr_obj_builder_free(builder);
            continue;
        }

        golden::File actual;
        actual.flags = expected.flags;

        // Recover the key table natively so texture identity and colour are part
        // of the comparison, not just geometry.
        WrObjKeyList* keys = wr_obj_keys_load(
                objContent.data(), static_cast<int32_t>(objContent.size()),
                mtlContent.empty() ? nullptr : mtlContent.data(),
                static_cast<int32_t>(mtlContent.size()),
                values.count("flipTextureV") > 0 ? 1 : 0);
        if (keys != nullptr) {
            for (int32_t i = 0; i < keys->count; i++) {
                golden::Key key;
                key.shaderType = keys->entries[i].shaderType;
                key.color = keys->entries[i].color;
                key.texture = keys->entries[i].textureString;
                actual.keys.push_back(key);
            }
            wr_obj_keys_free(keys);
        }

        const auto* draws = static_cast<const WrObjResultDraw*>(result->drawData);
        for (int32_t i = 0; i < result->drawCount; i++) {
            golden::Draw draw;
            draw.shaderType = draws[i].shaderType;
            draw.color = draws[i].color;
            draw.keyIndex = draws[i].keyIndex;
            draw.vertexOffset = draws[i].vertexOffset;
            draw.vertexCount = draws[i].vertexCount;
            draw.indexOffset = draws[i].indexOffset;
            draw.indexCount = draws[i].indexCount;
            actual.draws.push_back(draw);
        }
        const auto* vertices = static_cast<const uint8_t*>(result->vertexData);
        actual.vertexData.assign(vertices, vertices + result->vertexDataLength);
        const auto* indices = static_cast<const uint8_t*>(result->indexData);
        actual.indexData.assign(indices, indices + result->indexDataLength);
        actual.checksum = golden::computeChecksum(actual.vertexData, actual.indexData);

        const std::string difference = golden::diff(expected, actual);
        if (difference.empty()) {
            std::cout << "  OK   " << name << " (" << actual.vertexData.size() / 24
                      << " vertices, " << actual.indexData.size() / 4 << " indices, checksum 0x"
                      << std::hex << actual.checksum << std::dec << ")\n";
        } else {
            std::cout << "  FAIL " << name << "\n" << difference;
            failures++;
        }

        wr_obj_result_free(result);
        wr_obj_builder_free(builder);
    }

    return failures;
}

/// Enumerate `*.case` files in a directory. Kept dependency free because the test
/// binary links nothing but the C++ standard library.
std::vector<std::string> listCaseFiles(const std::string& directory) {
    std::vector<std::string> files;
#ifdef _WIN32
    const std::string pattern = directory + "\\*.case";
    WIN32_FIND_DATAA data;
    HANDLE handle = FindFirstFileA(pattern.c_str(), &data);
    if (handle == INVALID_HANDLE_VALUE) {
        return files;
    }
    do {
        files.push_back(directory + "/" + data.cFileName);
    } while (FindNextFileA(handle, &data));
    FindClose(handle);
#else
    DIR* dir = opendir(directory.c_str());
    if (dir == nullptr) {
        return files;
    }
    while (dirent* entry = readdir(dir)) {
        const std::string name(entry->d_name);
        if (name.size() > 5 && name.compare(name.size() - 5, 5, ".case") == 0) {
            files.push_back(directory + "/" + name);
        }
    }
    closedir(dir);
#endif
    std::sort(files.begin(), files.end());
    return files;
}

} // namespace

int main(int argc, char** argv) {
    std::cout << wr_obj_version_string() << ", ABI " << wr_obj_abi_version() << "\n\n";

    checkEqual(wr_obj_abi_version(), int32_t(WROBJ_ABI_VERSION), "ABI version matches the header");

    testParseComponents();
    testNegativeIndices();
    testIndexValidation();
    testAsciiDecoding();
    testGroupKeywordState();
    testMaterialGroups();
    testTriangulationFan();
    testDistinctKeepsLastBitNormalDifference();
    testDistinctScope();
    testDuplicateFacesCollapse();
    testGenerateNormals();
    testDegenerateNormalFallback();
    testExistingNormalPreserved();
    testScaleNormalCorrection();
    testMirrorFlipParity();
    testMaterialOptions();
    testMtlParsing();
    testTrInvertsDissolve();
    testMtlLineSplicing();
    testTextureStringNormalisation();
    testMaterialKeySurvivesPipeline();
    testKeyDiscovery();
    testColorPackingMatchesJvm();
    testEndToEndGeometry();
    testBuildIsDeterministic();
    testBuildIsRepeatable();
    testNoUsemtlYieldsNoKeys();
    testSeparateBuildersDoNotShare();

    if (argc > 1) {
        const std::string directory = argv[1];
        const std::vector<std::string> caseFiles = listCaseFiles(directory);
        if (caseFiles.empty()) {
            std::cout << "\ngolden directory " << directory
                      << " holds no .case files; run tools/generate-golden.ps1\n";
            gFailures++;
        } else {
            gFailures += runGolden(directory, caseFiles);
        }
    } else {
        std::cout << "\ngolden equivalence skipped (no directory argument)\n";
    }

    std::cout << "\n" << gChecks << " checks, " << gFailures << " failure(s)\n";
    return gFailures == 0 ? 0 : 1;
}
