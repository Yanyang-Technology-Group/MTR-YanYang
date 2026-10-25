// SPDX-License-Identifier: MIT
//
// wrobj_parse.cpp — OBJ and MTL readers that reproduce de.javagl.obj exactly.
//
// ObjReader and MtlReader have several behaviours that are easy to get subtly
// wrong and impossible to notice until a specific resource pack renders wrong.
// Each one is called out at the point where it is reproduced:
//
//   * OBJ text is decoded as US-ASCII, so bytes >= 0x80 become U+FFFD.
//   * A trailing backslash splices the next line, dropping two characters.
//   * `mtllib` keeps only the *last* file name in the document.
//   * `g` and `o` share one "current grouping keyword" state.
//   * Only the first `g`/`o` statement is honoured.
//   * `v`/`vt`/`vn` accept between one and four components; missing components
//     keep FloatTuple's 0.0 default, and a one-component `vt` is (u, 0).
//   * Face indices are relative to the *current* counts, so a face that
//     references vertices declared after it resolves differently.
//   * MTL lines are tokenised by `[ \t\n\r\f]+` and unrecognised tokens inside a
//     texture map statement overwrite the file name.
//   * MtlReader uses the platform default charset, not UTF-8.

#include "wrobj/wrobj_model.hpp"

#include <algorithm>
#include <cstdlib>
#include <cstring>
#include <limits>
#include <string>
#include <unordered_map>
#include <utility>
#include <vector>

namespace wrobj {
namespace {

constexpr const char* kReplacementChar = "\xEF\xBF\xBD"; // U+FFFD in UTF-8
constexpr const char* kWhitespace = " \t\n\r\f";

// ---------------------------------------------------------------------------
// Character decoding
// ---------------------------------------------------------------------------

/// Decode as StandardCharsets.US_ASCII: every byte with the high bit set becomes
/// U+FFFD, everything else is passed through.
std::string decodeAscii(const std::string& bytes) {
    bool needsExpansion = false;
    for (const unsigned char value : bytes) {
        if (value >= 0x80) {
            needsExpansion = true;
            break;
        }
    }
    if (!needsExpansion) {
        return bytes;
    }

    std::string out;
    out.reserve(bytes.size() + 16);
    for (const unsigned char value : bytes) {
        if (value >= 0x80) {
            out += kReplacementChar;
        } else {
            out.push_back(static_cast<char>(value));
        }
    }
    return out;
}

// ---------------------------------------------------------------------------
// Text helpers
// ---------------------------------------------------------------------------

/// Java String.trim(): strips characters <= U+0020 from both ends.
std::string_view trim(std::string_view text) {
    size_t begin = 0;
    size_t end = text.size();
    while (begin < end && static_cast<unsigned char>(text[begin]) <= 0x20) {
        begin++;
    }
    while (end > begin && static_cast<unsigned char>(text[end - 1]) <= 0x20) {
        end--;
    }
    return text.substr(begin, end - begin);
}

bool isDelimiter(char value) {
    return value == ' ' || value == '\t' || value == '\n' || value == '\r' || value == '\f';
}

/// Split on whitespace exactly like StringTokenizer with no delimiters returned.
void splitTokens(std::string_view text, std::vector<std::string_view>& out) {
    out.clear();
    size_t index = 0;
    while (index < text.size()) {
        while (index < text.size() && isDelimiter(text[index])) {
            index++;
        }
        if (index >= text.size()) {
            break;
        }
        const size_t start = index;
        while (index < text.size() && !isDelimiter(text[index])) {
            index++;
        }
        out.emplace_back(text.substr(start, index - start));
    }
}

std::string toLowerAscii(std::string_view text) {
    std::string out(text);
    for (char& value : out) {
        if (value >= 'A' && value <= 'Z') {
            value = static_cast<char>(value - 'A' + 'a');
        }
    }
    return out;
}

bool equalsIgnoreCase(std::string_view left, std::string_view right) {
    if (left.size() != right.size()) {
        return false;
    }
    for (size_t i = 0; i < left.size(); i++) {
        char a = left[i];
        char b = right[i];
        if (a >= 'A' && a <= 'Z') {
            a = static_cast<char>(a - 'A' + 'a');
        }
        if (b >= 'A' && b <= 'Z') {
            b = static_cast<char>(b - 'A' + 'a');
        }
        if (a != b) {
            return false;
        }
    }
    return true;
}

/// Java Float.parseFloat for the subset that appears in OBJ/MTL files.
/// Rejects anything Java would reject, including the empty string, because the
/// reference implementation turns those into an IOException or a NumberFormat
/// exception and the fallback path must agree.
bool parseJavaFloat(std::string_view token, float& out) {
    size_t index = 0;
    if (index < token.size() && (token[index] == '+' || token[index] == '-')) {
        index++;
    }
    if (index >= token.size()) {
        return false;
    }

    const std::string_view unsignedPart = token.substr(index);
    if (unsignedPart == "NaN") {
        out = std::numeric_limits<float>::quiet_NaN();
        return true;
    }
    if (unsignedPart == "Infinity") {
        out = token[0] == '-' ? -std::numeric_limits<float>::infinity()
                              : std::numeric_limits<float>::infinity();
        return true;
    }

    size_t digits = 0;
    bool seenDot = false;
    size_t cursor = index;
    while (cursor < token.size()) {
        const char value = token[cursor];
        if (value >= '0' && value <= '9') {
            digits++;
            cursor++;
        } else if (value == '.' && !seenDot) {
            seenDot = true;
            cursor++;
        } else {
            break;
        }
    }
    if (digits == 0) {
        return false;
    }

    if (cursor < token.size() && (token[cursor] == 'e' || token[cursor] == 'E')) {
        size_t exponentCursor = cursor + 1;
        if (exponentCursor < token.size()
                && (token[exponentCursor] == '+' || token[exponentCursor] == '-')) {
            exponentCursor++;
        }
        size_t exponentDigits = 0;
        while (exponentCursor < token.size()
                && token[exponentCursor] >= '0' && token[exponentCursor] <= '9') {
            exponentDigits++;
            exponentCursor++;
        }
        if (exponentDigits == 0) {
            return false;
        }
        cursor = exponentCursor;
    }

    if (cursor < token.size() && (token[cursor] == 'f' || token[cursor] == 'F')) {
        cursor++;
    }
    if (cursor != token.size()) {
        return false;
    }

    const std::string owned(token);
    char* parseEnd = nullptr;
    const float parsed = std::strtof(owned.c_str(), &parseEnd);
    if (parseEnd != owned.c_str() + owned.size()) {
        return false;
    }
    out = parsed;
    return true;
}

bool parseIntToken(std::string_view token, int32_t& out) {
    if (token.empty()) {
        return false;
    }
    const std::string owned(token);
    char* parseEnd = nullptr;
    const long parsed = std::strtol(owned.c_str(), &parseEnd, 10);
    if (parseEnd != owned.c_str() + owned.size()) {
        return false;
    }
    out = static_cast<int32_t>(parsed);
    return true;
}

/// Emulate BufferedReader.readLine(): a line ends at '\n', '\r' or "\r\n".
/// Unpaired '\r' also terminates, which is what Java does.
void splitLines(const std::string& text, std::vector<std::string_view>& out) {
    out.clear();
    size_t index = 0;
    while (index < text.size()) {
        const size_t start = index;
        while (index < text.size() && text[index] != '\n' && text[index] != '\r') {
            index++;
        }
        out.emplace_back(text.data() + start, index - start);
        if (index < text.size()) {
            if (text[index] == '\r' && index + 1 < text.size() && text[index + 1] == '\n') {
                index += 2;
            } else {
                index++;
            }
        }
    }
}

/// ObjReader's backslash splice: trim, and while it ends with '\', drop two
/// characters and append the next raw line separated by a space.
std::string spliceLines(const std::vector<std::string_view>& lines, size_t& cursor) {
    if (cursor >= lines.size()) {
        return std::string();
    }
    std::string line(trim(lines[cursor]));
    cursor++;
    while (!line.empty() && line.back() == '\\' && line.size() >= 2) {
        line.resize(line.size() - 2);
        if (cursor >= lines.size()) {
            break;
        }
        line += ' ';
        line += std::string(lines[cursor]);
        cursor++;
    }
    return line;
}

/// MtlReader has a subtly different splice: it keeps the raw (already trimmed)
/// string and, after joining, trims the whole result again.
std::string spliceMtlLines(const std::vector<std::string_view>& lines, size_t& cursor, bool& finished) {
    finished = false;
    if (cursor >= lines.size()) {
        finished = true;
        return std::string();
    }
    std::string line(trim(lines[cursor]));
    cursor++;
    while (!line.empty() && line.back() == '\\' && line.size() >= 2) {
        line.resize(line.size() - 2);
        if (cursor >= lines.size()) {
            finished = true;
            return line;
        }
        line += ' ';
        line += std::string(lines[cursor]);
        cursor++;
    }
    return line;
}

// ---------------------------------------------------------------------------
// Float tuple reading (Utils.readFloatTuple / Utils.parseFloats)
// ---------------------------------------------------------------------------

/// A component is absent when the token is missing or not a valid Java float.
/// Utils.parseFloat throws in that case, and Utils.parseFloats leaves the slot
/// null, so the two paths agree on what "absent" means.
struct OptionalFloats {
    bool present[4] = {false, false, false, false};
    float value[4] = {0.0F, 0.0F, 0.0F, 0.0F};

    int count() const {
        for (int i = 0; i < 4; i++) {
            if (!present[i]) {
                return i;
            }
        }
        return 4;
    }
};

void readOptionalFloats(const std::vector<std::string_view>& tokens, size_t firstToken, OptionalFloats& out) {
    for (int i = 0; i < 4; i++) {
        const size_t index = firstToken + static_cast<size_t>(i);
        if (index >= tokens.size()) {
            break;
        }
        float parsed = 0.0F;
        if (!parseJavaFloat(tokens[index], parsed)) {
            break;
        }
        out.present[i] = true;
        out.value[i] = parsed;
    }
}

} // namespace

// ---------------------------------------------------------------------------
// Java-compatible hash and equality
// ---------------------------------------------------------------------------

/// Java Float.floatToIntBits: the raw IEEE-754 bits, with NaN canonicalised.
///
/// Declared before floatEqualsJava because that comparison is defined in terms of these bits.
/// The -0.0 case is the point: its bits differ from +0.0 while C++ == says they are equal.
static int32_t floatBits(float value) {
    if (value != value) {
        return 0x7FC00000;
    }
    int32_t bits = 0;
    std::memcpy(&bits, &value, sizeof(bits));
    return bits;
}

/// Java Float.compare(a, b) == 0 for two float fields, compared by bit pattern.
///
/// Not the same as C++ operator==, and the difference is load-bearing here. Float.compare orders
/// -0.0 below +0.0, so the two are *not* equal, while C++ == reports them equal because IEEE 754
/// says they compare equal. A vertex carrying -0.0 in any component is therefore a distinct
/// vertex to the JVM and a duplicate to a port using ==.
///
/// This is reachable in ordinary use rather than being a corner case: a negative scale multiplies
/// a coordinate of +0.0 into -0.0. The model-scale-negative case does exactly that with its z
/// scale of -1, and the JVM kept 12 distinct vertices where an ==-based port kept 8.
///
/// Comparing bit patterns reproduces Float.compare exactly, except for NaN, which Java
/// canonicalises in floatToIntBits and which is handled here the same way.
static bool floatEqualsJava(float a, float b) {
    const int32_t bitsA = floatBits(a);
    const int32_t bitsB = floatBits(b);
    return bitsA == bitsB;
}

bool vertexEquals(const Vertex& a, const Vertex& b) {
    return floatEqualsJava(a.u, b.u) && floatEqualsJava(a.v, b.v)
            && floatEqualsJava(a.position.x, b.position.x)
            && floatEqualsJava(a.position.y, b.position.y)
            && floatEqualsJava(a.position.z, b.position.z)
            && floatEqualsJava(a.normal.x, b.normal.x)
            && floatEqualsJava(a.normal.y, b.normal.y)
            && floatEqualsJava(a.normal.z, b.normal.z);
}

namespace {

/// Java Float.hashCode(): the raw IEEE-754 bits with NaN canonicalised.
int32_t floatHashBits(float value) {
    if (value != value) {
        return 0x7FC00000;
    }
    int32_t bits = 0;
    std::memcpy(&bits, &value, sizeof(bits));
    return bits;
}

uint64_t mixHash(uint64_t hash, uint64_t value) {
    hash ^= value;
    hash *= 0x100000001B3ULL;
    return hash;
}

} // namespace

uint64_t vertexHash(const Vertex& vertex) {
    // Object.hash(position, normal, u, v) over Objects.hash-coded components.
    // Values are exact rather than Java's modulo-2^32 packing, but the ordering
    // guarantee that matters is only "equal vertices hash equally".
    uint64_t hash = 0xCBF29CE484222325ULL;
    hash = mixHash(hash, static_cast<uint64_t>(static_cast<uint32_t>(floatHashBits(vertex.position.x))));
    hash = mixHash(hash, static_cast<uint64_t>(static_cast<uint32_t>(floatHashBits(vertex.position.y))));
    hash = mixHash(hash, static_cast<uint64_t>(static_cast<uint32_t>(floatHashBits(vertex.position.z))));
    hash = mixHash(hash, static_cast<uint64_t>(static_cast<uint32_t>(floatHashBits(vertex.normal.x))));
    hash = mixHash(hash, static_cast<uint64_t>(static_cast<uint32_t>(floatHashBits(vertex.normal.y))));
    hash = mixHash(hash, static_cast<uint64_t>(static_cast<uint32_t>(floatHashBits(vertex.normal.z))));
    hash = mixHash(hash, static_cast<uint64_t>(static_cast<uint32_t>(floatHashBits(vertex.u))));
    hash = mixHash(hash, static_cast<uint64_t>(static_cast<uint32_t>(floatHashBits(vertex.v))));
    return hash;
}

// ---------------------------------------------------------------------------
// ObjDocument lookup helpers
// ---------------------------------------------------------------------------

int32_t ObjDocument::findGroup(const std::string& name) const {
    for (size_t i = 0; i < groups.size(); i++) {
        if (groups[i].name == name) {
            return static_cast<int32_t>(i);
        }
    }
    return -1;
}

int32_t ObjDocument::findMaterialGroup(const std::string& name) const {
    for (size_t i = 0; i < materialGroups.size(); i++) {
        if (materialGroups[i].name == name) {
            return static_cast<int32_t>(i);
        }
    }
    return -1;
}

// ---------------------------------------------------------------------------
// OBJ parsing
// ---------------------------------------------------------------------------

bool parseObj(const std::string& content, ObjDocument& out) {
    out = ObjDocument();
    const std::string decoded = decodeAscii(content);

    std::vector<std::string_view> lines;
    splitLines(decoded, lines);

    // DefaultObj's constructor installs a "default" group before parsing starts,
    // so a file with no `g` statement still yields one group named "default".
    out.groups.push_back(ObjGroup{"default", {}});
    out.groupCreatedAtFace.push_back(-1);
    out.materialGroups.push_back(ObjGroup{"default", {}});

    // Group names are owned strings, not views into the current line: the line
    // buffer is reused for the next statement, and DefaultObj keeps the names for
    // the lifetime of the document.
    std::vector<std::string> activeGroupNames{"default"};
    std::string activeMaterialGroupName;
    std::string groupingKeyword;
    bool groupingKeywordSet = false;

    std::vector<std::string_view> tokens;
    size_t cursor = 0;

    while (cursor < lines.size()) {
        const std::string line = spliceLines(lines, cursor);
        const std::string_view trimmedLine = trim(line);
        if (trimmedLine.empty()) {
            continue;
        }

        splitTokens(trimmedLine, tokens);
        if (tokens.empty()) {
            continue;
        }

        const std::string identifier = toLowerAscii(tokens[0]);

        if (identifier == "v" || identifier == "vt" || identifier == "vn") {
            OptionalFloats values;
            readOptionalFloats(tokens, 1, values);
            if (values.count() == 0) {
                // Utils.readFloatTuple throws on a malformed component, and
                // ObjReader propagates it, aborting the whole read.
                return false;
            }
            if (identifier == "v") {
                out.vertices.push_back(Vec3f(values.value[0], values.value[1], values.value[2]));
                // A face may reference this vertex before the counter advances,
                // exactly like ObjReader's vertexCounter.
            } else if (identifier == "vt") {
                out.texCoords.push_back(Vec3f(values.value[0], values.value[1], values.value[2]));
            } else {
                out.normals.push_back(Vec3f(values.value[0], values.value[1], values.value[2]));
            }
            continue;
        }

        if (identifier == "mtllib") {
            // ObjReader keeps only the last `mtllib` name and only ever one of
            // them. The caller resolves the MTL content, so this statement is
            // recorded as "seen" and otherwise ignored.
            out.hasMtlLib = true;
            continue;
        }

        if (identifier == "usemtl") {
            const size_t offset = std::min<size_t>(6, line.size());
            const std::string name(trim(std::string_view(line).substr(offset)));
            if (!name.empty()) {
                activeMaterialGroupName = name;
            }
            continue;
        }

        if (identifier == "g" || identifier == "o") {
            // ObjReader tracks one "groupOrObject" keyword: a different keyword
            // is ignored until it appears again.
            if (groupingKeywordSet && groupingKeyword != identifier) {
                continue;
            }
            groupingKeyword = identifier;
            groupingKeywordSet = true;
            const size_t offset = std::min<size_t>(1, line.size());
            const std::string_view remainder = trim(std::string_view(line).substr(offset));
            std::vector<std::string_view> names;
            splitTokens(remainder, names);
            activeGroupNames.clear();
            if (names.empty()) {
                // DefaultObj.setActiveGroupNames replaces an empty name list with
                // the "default" group.
                activeGroupNames.emplace_back("default");
            } else {
                for (const std::string_view name : names) {
                    activeGroupNames.emplace_back(name);
                }
            }
            continue;
        }

        if (identifier != "f") {
            continue;
        }

        // --- ObjFaceParser: strict index syntax on the raw (untrimmed) line ---
        std::vector<int32_t> vertexIndices;
        std::vector<int32_t> texCoordIndices;
        std::vector<int32_t> normalIndices;
        bool foundTexCoords = false;
        bool foundNormals = false;

        {
            const char* data = line.c_str();
            size_t length = line.size();
            size_t index = 0;
            auto skipSpaces = [&]() {
                while (index < length && data[index] == ' ') {
                    index++;
                }
            };
            auto parseNonzeroInt = [&]() -> int32_t {
                bool negate = false;
                if (index < length && data[index] == '-') {
                    negate = true;
                    index++;
                    skipSpaces();
                    if (index >= length) {
                        return 0;
                    }
                }
                int32_t parsed = 0;
                if (index < length && data[index] >= '0' && data[index] <= '9') {
                    parsed = data[index] - '0';
                    index++;
                    while (index < length && data[index] >= '0' && data[index] <= '9') {
                        parsed = parsed * 10 + (data[index] - '0');
                        index++;
                    }
                }
                return negate ? -parsed : parsed;
            };

            // ObjFaceParser.skipSpaces() then the 'f'/'F' check. Note that
            // ObjReader already trimmed the line, so the leading spaces the
            // parser skips are the ones after the 'f' keyword.
            skipSpaces();
            if (index >= length || (data[index] != 'f' && data[index] != 'F')) {
                return false; // ObjFaceParser throws IOException
            }
            index++;

            int32_t count = 0;
            while (true) {
                skipSpaces();
                if (index >= length) {
                    break;
                }
                const int32_t vertexIndex = parseNonzeroInt();
                if (vertexIndex == 0) {
                    return false; // "Could not read vertex index"
                }
                if (count >= static_cast<int32_t>(vertexIndices.size())) {
                    vertexIndices.push_back(vertexIndex);
                    texCoordIndices.push_back(0);
                    normalIndices.push_back(0);
                } else {
                    vertexIndices[static_cast<size_t>(count)] = vertexIndex;
                }
                count++;

                skipSpaces();
                if (index >= length || data[index] != '/') {
                    continue;
                }
                index++;
                skipSpaces();
                if (index >= length) {
                    return false; // "Unexpected end of input after '/'"
                }
                const int32_t texCoordIndex = parseNonzeroInt();
                if (texCoordIndex != 0) {
                    texCoordIndices[static_cast<size_t>(count - 1)] = texCoordIndex;
                    foundTexCoords = true;
                }

                skipSpaces();
                if (index >= length || data[index] != '/') {
                    continue;
                }
                index++;
                skipSpaces();
                if (index >= length) {
                    return false;
                }
                const int32_t normalIndex = parseNonzeroInt();
                if (normalIndex == 0) {
                    return false; // "Could not read normal index"
                }
                normalIndices[static_cast<size_t>(count - 1)] = normalIndex;
                foundNormals = true;
            }

            // ObjFaceParser.getVertexIndices() copies the first `count` entries.
            // A count larger than the buffer is impossible here because the buffer
            // also holds a slot for every index pushed so far.
            vertexIndices.resize(static_cast<size_t>(count));
            texCoordIndices.resize(foundTexCoords ? static_cast<size_t>(count) : 0);
            normalIndices.resize(foundNormals ? static_cast<size_t>(count) : 0);
        }

        if (vertexIndices.empty()) {
            continue;
        }

        // ObjReader.makeIndicesAbsolute, evaluated against the counts as they are
        // *at this point in the file*, not the final totals.
        const int32_t vertexCount = static_cast<int32_t>(out.vertices.size());
        const int32_t texCoordCount = static_cast<int32_t>(out.texCoords.size());
        const int32_t normalCount = static_cast<int32_t>(out.normals.size());
        for (int32_t& value : vertexIndices) {
            value = value < 0 ? value + vertexCount : value - 1;
        }
        for (int32_t& value : texCoordIndices) {
            value = value < 0 ? value + texCoordCount : value - 1;
        }
        for (int32_t& value : normalIndices) {
            value = value < 0 ? value + normalCount : value - 1;
        }

        // DefaultObj.addFace: rejected here because checkIndices() throws.
        for (const int32_t value : vertexIndices) {
            if (value < 0 || value >= vertexCount) {
                return false;
            }
        }
        for (const int32_t value : texCoordIndices) {
            if (value < 0 || value >= texCoordCount) {
                return false;
            }
        }
        for (const int32_t value : normalIndices) {
            if (value < 0 || value >= normalCount) {
                return false;
            }
        }

        const size_t faceIndex = out.faces.size();

        // DefaultObj.addFace: the face joins the active material group when one is
        // active. Note that the "default" material group exists from construction
        // but stays empty unless a `usemtl default` statement names it, because
        // activeMaterialGroup stays null until the first usemtl.
        if (!activeMaterialGroupName.empty()) {
            int32_t materialIndex = out.findMaterialGroup(activeMaterialGroupName);
            if (materialIndex < 0) {
                out.materialGroups.push_back(ObjGroup{activeMaterialGroupName, {}});
                materialIndex = static_cast<int32_t>(out.materialGroups.size()) - 1;
            }
            out.materialGroups[static_cast<size_t>(materialIndex)].faceIndices.push_back(
                    static_cast<int32_t>(faceIndex));
        }

        // DefaultObj.addFace also assigns the face to every active group, creating
        // the group objects on first use.
        for (const std::string& name : activeGroupNames) {
            int32_t groupIndex = out.findGroup(name);
            if (groupIndex < 0) {
                out.groups.push_back(ObjGroup{name, {}});
                groupIndex = static_cast<int32_t>(out.groups.size()) - 1;
                out.groupCreatedAtFace.push_back(static_cast<int32_t>(faceIndex));
            }
            out.groups[static_cast<size_t>(groupIndex)].faceIndices.push_back(static_cast<int32_t>(faceIndex));
        }

        ParsedFace face;
        face.v = std::move(vertexIndices);
        face.vt = std::move(texCoordIndices);
        face.vn = std::move(normalIndices);
        // The material group stays with the face so that later stages can split
        // by material exactly where the reference implementation does.
        face.materialKey = activeMaterialGroupName;
        out.faces.push_back(std::move(face));
    }

    return true;
}

// ---------------------------------------------------------------------------
// MTL parsing
// ---------------------------------------------------------------------------

namespace {

void applyTextureOptions(Mtl& mtl, const std::vector<std::string_view>& tokens, size_t firstToken) {
    // MtlReader.readTextureOptions: options are consumed with their arguments and
    // every other token *overwrites* the file name, so the last bare token wins.
    size_t index = firstToken;
    while (index < tokens.size()) {
        const std::string_view option = tokens[index];
        index++;
        if (equalsIgnoreCase(option, "-blendu") || equalsIgnoreCase(option, "-blendv")
                || equalsIgnoreCase(option, "-cc") || equalsIgnoreCase(option, "-clamp")) {
            index++;
        } else if (equalsIgnoreCase(option, "-boost") || equalsIgnoreCase(option, "-texres")
                || equalsIgnoreCase(option, "-bm") || equalsIgnoreCase(option, "-imfchan")
                || equalsIgnoreCase(option, "-type")) {
            index++;
        } else if (equalsIgnoreCase(option, "-mm")) {
            index += 2;
        } else if (equalsIgnoreCase(option, "-o") || equalsIgnoreCase(option, "-s")
                || equalsIgnoreCase(option, "-t")) {
            index += 3;
        } else {
            mtl.mapKd = std::string(option);
        }
    }
}

} // namespace

bool parseMtl(const std::string& content, std::vector<Mtl>& out) {
    out.clear();

    // MtlReader.read(InputStream) uses the platform default charset. On Windows
    // that is a code page, on Linux usually UTF-8; MTL files reference texture
    // paths, so a non-ASCII byte only ever affects a path that would not resolve
    // anyway. Bytes are passed through unchanged.
    std::vector<std::string_view> lines;
    splitLines(content, lines);

    std::vector<std::string_view> tokens;
    size_t cursor = 0;
    size_t currentIndex = static_cast<size_t>(-1);

    while (cursor < lines.size()) {
        bool finished = false;
        std::string line = spliceMtlLines(lines, cursor, finished);
        if (finished) {
            break;
        }
        const std::string_view trimmedLine = trim(line);
        if (trimmedLine.empty()) {
            continue;
        }

        if (trimmedLine.size() >= 6 && trimmedLine.substr(0, 6) == "newmtl") {
            const std::string name(trim(trimmedLine.substr(6)));
            out.push_back(Mtl{});
            out.back().name = name;
            currentIndex = out.size() - 1;
            continue;
        }

        if (trimmedLine[0] == '#') {
            continue;
        }
        if (currentIndex == static_cast<size_t>(-1)) {
            return false; // MtlReader throws "Missing newmtl statement before ..."
        }

        splitTokens(trimmedLine, tokens);
        if (tokens.empty()) {
            continue;
        }
        Mtl& mtl = out[currentIndex];
        const std::string_view command = tokens[0];

        if (equalsIgnoreCase(command, "Kd")) {
            OptionalFloats values;
            readOptionalFloats(tokens, 1, values);
            if (values.present[0]) {
                mtl.hasKd = true;
                mtl.kdR = values.value[0];
                mtl.kdG = values.value[1];
                mtl.kdB = values.value[2];
            }
        } else if (equalsIgnoreCase(command, "d")) {
            size_t index = 1;
            if (index < tokens.size() && equalsIgnoreCase(tokens[index], "-halo")) {
                index++;
            }
            float value = 0.0F;
            if (index < tokens.size() && parseJavaFloat(tokens[index], value)) {
                mtl.hasDissolve = true;
                mtl.dissolve = value;
            }
        } else if (equalsIgnoreCase(command, "Tr")) {
            float value = 0.0F;
            if (tokens.size() > 1 && parseJavaFloat(tokens[1], value)) {
                mtl.hasDissolve = true;
                mtl.dissolve = 1.0F - value;
            }
        } else if (equalsIgnoreCase(command, "map_Kd")) {
            applyTextureOptions(mtl, tokens, 1);
        }
        // Every other statement is irrelevant to this pipeline. Ka/Ks/Kd/Ns/etc.
        // are parsed by the reference reader but never read by ObjModelLoader.
    }

    return true;
}

} // namespace wrobj
