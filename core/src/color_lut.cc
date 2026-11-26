#include "prooftray/color_lut.h"

#include <algorithm>
#include <bit>
#include <cmath>
#include <iomanip>
#include <limits>
#include <locale>
#include <sstream>
#include <stdexcept>

namespace prooftray {
namespace {

constexpr float kMaxLutMagnitude = 65504.0f;

float Clamp(float value) { return std::clamp(value, 0.0f, 1.0f); }

float Linear(float value) {
  return value <= 0.04045f ? value / 12.92f : std::pow((value + 0.055f) / 1.055f, 2.4f);
}

float Encoded(float value) {
  return value <= 0.0031308f ? value * 12.92f : 1.055f * std::pow(value, 1.0f / 2.4f) - 0.055f;
}

float Exposure(float value, float gain) { return Clamp(Encoded(Linear(value) * gain)); }

std::string StripComment(std::string line) {
  bool quoted = false;
  bool escaped = false;
  for (size_t index = 0; index < line.size(); ++index) {
    const char value = line[index];
    if (!escaped && value == '"') quoted = !quoted;
    if (!quoted && value == '#') return line.substr(0, index);
    escaped = !escaped && value == '\\';
  }
  return line;
}

Rgb Vertex(const ColorLut& lut, int red, int green, int blue) {
  const size_t index = ((static_cast<size_t>(blue) * lut.size + green) * lut.size + red) * 3;
  return {lut.values[index], lut.values[index + 1], lut.values[index + 2]};
}

}  // namespace

void ValidateLut(const ColorLut& lut) {
  if ((lut.dimension != 1 && lut.dimension != 3) || lut.size < 2 ||
      (lut.dimension == 1 && lut.size > 65536) || (lut.dimension == 3 && lut.size > 65)) {
    throw std::invalid_argument("Supported LUT sizes: 1D 2..65536, 3D 2..65");
  }
  const size_t count = lut.dimension == 1 ? lut.size : static_cast<size_t>(lut.size) * lut.size * lut.size;
  if (lut.values.size() != count * 3) throw std::invalid_argument("LUT row count does not match its declared size");
  for (size_t channel = 0; channel < 3; ++channel) {
    if (!std::isfinite(lut.domain_min[channel]) || !std::isfinite(lut.domain_max[channel]) ||
        std::abs(lut.domain_min[channel]) > kMaxLutMagnitude || std::abs(lut.domain_max[channel]) > kMaxLutMagnitude ||
        lut.domain_max[channel] - lut.domain_min[channel] < std::numeric_limits<float>::min() ||
        lut.domain_max[channel] <= lut.domain_min[channel]) throw std::invalid_argument("Invalid LUT input domain");
  }
  for (float value : lut.values) {
    if (!std::isfinite(value) || std::abs(value) > kMaxLutMagnitude) throw std::invalid_argument("LUT values exceed the supported finite range");
  }
}

ColorLut ParseCube(std::string_view source) {
  if (source.size() > 32 * 1024 * 1024) throw std::invalid_argument("Cube file exceeds 32 MiB");
  if (source.starts_with("\xef\xbb\xbf")) source.remove_prefix(3);
  ColorLut result;
  std::istringstream input{std::string(source)};
  input.imbue(std::locale::classic());
  std::string line;
  bool rows_started = false;
  bool input_range = false;
  bool explicit_domain = false;
  int range_dimension = 0;
  while (std::getline(input, line)) {
    if (line.size() > 65536) throw std::invalid_argument("Cube line is too long");
    std::istringstream stream(StripComment(line));
    stream.imbue(std::locale::classic());
    std::string key;
    if (!(stream >> key)) continue;
    if (key == "TITLE") {
      if (rows_started || !(stream >> std::quoted(result.title))) throw std::invalid_argument("Invalid TITLE header");
    } else if (key == "LUT_3D_SIZE" || key == "LUT_1D_SIZE") {
      if (result.size != 0 || rows_started) throw std::invalid_argument("Combined 1D+3D cubes are not supported");
      result.dimension = key == "LUT_1D_SIZE" ? 1 : 3;
      if (!(stream >> result.size) || result.size < 2 || result.size > (result.dimension == 1 ? 65536 : 65)) {
        throw std::invalid_argument("Invalid LUT size");
      }
      const size_t count = result.dimension == 1 ? result.size : static_cast<size_t>(result.size) * result.size * result.size;
      result.values.reserve(count * 3);
    } else if (key == "DOMAIN_MIN" || key == "DOMAIN_MAX") {
      if (rows_started || input_range) throw std::invalid_argument("Conflicting LUT domain headers");
      explicit_domain = true;
      auto& domain = key == "DOMAIN_MIN" ? result.domain_min : result.domain_max;
      if (!(stream >> domain[0] >> domain[1] >> domain[2])) throw std::invalid_argument("Invalid LUT domain header");
    } else if (key == "LUT_1D_INPUT_RANGE" || key == "LUT_3D_INPUT_RANGE") {
      float minimum = 0, maximum = 0;
      if (rows_started || explicit_domain || input_range || !(stream >> minimum >> maximum)) {
        throw std::invalid_argument("Invalid or conflicting INPUT_RANGE");
      }
      input_range = true;
      range_dimension = key == "LUT_1D_INPUT_RANGE" ? 1 : 3;
      result.domain_min.fill(minimum); result.domain_max.fill(maximum);
    } else {
      if (result.size == 0) throw std::invalid_argument("Missing LUT size header");
      rows_started = true;
      std::istringstream numbers(key);
      numbers.imbue(std::locale::classic());
      Rgb values{};
      if (!(numbers >> values[0]) || !(stream >> values[1] >> values[2])) throw std::invalid_argument("Invalid LUT data row or directive");
      std::string extra;
      if (numbers >> extra) throw std::invalid_argument("Invalid LUT number");
      const size_t count = result.dimension == 1 ? result.size : static_cast<size_t>(result.size) * result.size * result.size;
      if (result.values.size() >= count * 3) throw std::invalid_argument("Too many LUT rows");
      result.values.insert(result.values.end(), values.begin(), values.end());
    }
    std::string extra;
    if (stream >> extra) throw std::invalid_argument("Unexpected data after cube fields");
  }
  if (range_dimension && range_dimension != result.dimension) throw std::invalid_argument("INPUT_RANGE dimension mismatch");
  result.domain_declared = explicit_domain || input_range;
  ValidateLut(result);
  return result;
}

std::vector<uint8_t> SerializeLut(const ColorLut& lut) {
  ValidateLut(lut);
  std::vector<uint8_t> bytes{'O', 'B', 'L', 'U', 'T', '1', 0, 0};
  auto word = [&](uint32_t value) { for (int byte = 0; byte < 4; ++byte) bytes.push_back(static_cast<uint8_t>(value >> (byte * 8))); };
  word(static_cast<uint32_t>(lut.dimension)); word(static_cast<uint32_t>(lut.size));
  for (float value : lut.domain_min) word(std::bit_cast<uint32_t>(value));
  for (float value : lut.domain_max) word(std::bit_cast<uint32_t>(value));
  for (float value : lut.values) word(std::bit_cast<uint32_t>(value));
  return bytes;
}

ColorLut ParseLutBinary(std::span<const uint8_t> bytes) {
  const std::array<uint8_t, 8> magic{'O', 'B', 'L', 'U', 'T', '1', 0, 0};
  if (bytes.size() < 40 || !std::equal(magic.begin(), magic.end(), bytes.begin())) throw std::invalid_argument("Invalid ProofTray LUT header");
  size_t offset = 8;
  auto word = [&]() {
    if (bytes.size() - offset < 4) throw std::invalid_argument("Truncated LUT data");
    uint32_t value = 0;
    for (int byte = 0; byte < 4; ++byte) value |= uint32_t{bytes[offset++]} << (byte * 8);
    return value;
  };
  ColorLut lut;
  lut.dimension = static_cast<int>(word()); lut.size = static_cast<int>(word());
  if ((lut.dimension != 1 && lut.dimension != 3) || lut.size < 2 || lut.size > (lut.dimension == 1 ? 65536 : 65)) {
    throw std::invalid_argument("Invalid binary LUT dimensions");
  }
  for (auto& value : lut.domain_min) value = std::bit_cast<float>(word());
  for (auto& value : lut.domain_max) value = std::bit_cast<float>(word());
  const size_t count = (lut.dimension == 1 ? lut.size : static_cast<size_t>(lut.size) * lut.size * lut.size) * 3;
  if (bytes.size() != 40 + count * 4) throw std::invalid_argument("Binary LUT length mismatch");
  lut.values.reserve(count);
  for (size_t index = 0; index < count; ++index) lut.values.push_back(std::bit_cast<float>(word()));
  ValidateLut(lut);
  return lut;
}

ColorLut FromHaldRgba(std::span<const uint8_t> pixels, size_t side, size_t stride) {
  int level = 0;
  for (int candidate = 2; candidate <= 12; ++candidate) if (static_cast<size_t>(candidate * candidate * candidate) == side) level = candidate;
  if (!level || stride < side * 4 || stride > pixels.size() / side) throw std::invalid_argument("Hald PNG side must be level cubed (levels 2..12)");
  ColorLut source;
  source.size = level * level;
  source.values.reserve(side * side * 3);
  for (size_t y = 0; y < side; ++y) {
    for (size_t x = 0; x < side; ++x) {
      const auto* pixel = pixels.data() + y * stride + x * 4;
      if (pixel[3] != 255) throw std::invalid_argument("Hald images must be opaque RGB data");
      for (int channel = 0; channel < 3; ++channel) source.values.push_back(pixel[channel] / 255.0f);
    }
  }
  if (source.size <= 65) { ValidateLut(source); return source; }
  ColorLut result;
  result.size = 65;
  result.values.reserve(65 * 65 * 65 * 3);
  for (int blue = 0; blue < 65; ++blue) for (int green = 0; green < 65; ++green) for (int red = 0; red < 65; ++red) {
    const auto sample = SampleLut(source, {red / 64.0f, green / 64.0f, blue / 64.0f});
    result.values.insert(result.values.end(), sample.begin(), sample.end());
  }
  ValidateLut(result);
  return result;
}

void ValidateLook(const ColorLook& look) {
  // Photos use a LUT either directly or as a Rec.709 Gamma 2.4 video LUT on both ends.
  if (look.input_encoding != look.output_encoding ||
      (look.input_encoding != LutEncoding::Direct && look.input_encoding != LutEncoding::Rec709Gamma24)) {
    throw std::invalid_argument("Unsupported display LUT encoding");
  }
  ValidateLookParameters(look);
}

void ValidateLookParameters(const ColorLook& look) {
  if (!std::isfinite(look.tint) || std::abs(look.tint) > 1) throw std::invalid_argument("Invalid tint");
  ValidateColorAdjustments(look.adjustments);
  const std::array<float, 5> fields{look.strength, look.exposure_ev, look.contrast, look.saturation, look.warmth};
  for (float value : fields) if (!std::isfinite(value)) throw std::invalid_argument("Look parameters must be finite");
  if (look.strength < 0 || look.strength > 1 || look.exposure_ev < -3 || look.exposure_ev > 3 || look.contrast < -1 || look.contrast > 1 ||
      look.saturation < 0 || look.saturation > 2 || look.warmth < -1 || look.warmth > 1) throw std::invalid_argument("Look parameter outside range");
}

Rgb SampleLut(const ColorLut& lut, const Rgb& input) {
  std::array<int, 3> base{};
  Rgb fraction{};
  for (size_t channel = 0; channel < 3; ++channel) {
    const float position = Clamp((input[channel] - lut.domain_min[channel]) / (lut.domain_max[channel] - lut.domain_min[channel])) * (lut.size - 1);
    base[channel] = std::min(static_cast<int>(position), lut.size - 2);
    fraction[channel] = position - base[channel];
  }
  if (lut.dimension == 1) {
    Rgb result{};
    for (size_t channel = 0; channel < 3; ++channel) {
      const float first = lut.values[base[channel] * 3 + channel];
      result[channel] = first + fraction[channel] * (lut.values[(base[channel] + 1) * 3 + channel] - first);
    }
    return result;
  }
  // Tetrahedral interpolation: order the three fractional coordinates, then visit four lattice vertices.
  std::array<int, 3> order{0, 1, 2};
  if (fraction[order[0]] < fraction[order[1]]) std::swap(order[0], order[1]);
  if (fraction[order[1]] < fraction[order[2]]) std::swap(order[1], order[2]);
  if (fraction[order[0]] < fraction[order[1]]) std::swap(order[0], order[1]);
  Rgb previous = Vertex(lut, base[0], base[1], base[2]);
  Rgb result = previous;
  for (int axis : order) {
    ++base[axis];
    const Rgb next = Vertex(lut, base[0], base[1], base[2]);
    for (size_t channel = 0; channel < 3; ++channel) result[channel] += fraction[axis] * (next[channel] - previous[channel]);
    previous = next;
  }
  return result;
}

Rgb ApplyDisplayLut(const Rgb& srgb, const ColorLut* lut, float strength, LutEncoding input, LutEncoding output) {
  if (!lut || strength == 0) return srgb;
  auto color = srgb;
  if (input == LutEncoding::Rec709Gamma24) for (float& c : color) c = std::pow(Linear(c), 1.f / 2.4f);
  color = SampleLut(*lut, color);
  if (output == LutEncoding::Rec709Gamma24) {
    for (float& c : color) c = Encoded(std::pow(std::max(0.f, c), 2.4f));
  }
  for (int c = 0; c < 3; ++c) color[c] = Clamp(std::lerp(srgb[c], Clamp(color[c]), strength));
  return color;
}

Rgb ApplyColorLook(const Rgb& srgb, const ColorLook& look, const ColorLut* lut) {
  Rgb color = srgb;
  if (look.exposure_ev != 0 || look.warmth != 0 || look.tint != 0) {
    const Rgb gains{std::exp2(look.exposure_ev + look.warmth * .25f), std::exp2(look.exposure_ev - look.tint * .25f),
                    std::exp2(look.exposure_ev - look.warmth * .25f)};
    for (int c = 0; c < 3; ++c) color[c] = Exposure(color[c], gains[c]);
  }
  return ApplyDisplayAdjustments(ApplyDisplayLut(color, lut, look.strength, look.input_encoding, look.output_encoding), look);
}

void ApplyColorLookRgba(std::span<uint8_t> pixels, size_t width, size_t height, size_t stride, bool premultiplied,
                        const ColorLook& look, const ColorLut* lut) {
  if (width > std::numeric_limits<size_t>::max() / 4 || stride < width * 4 || (height && stride > pixels.size() / height)) {
    throw std::invalid_argument("Invalid RGBA image dimensions");
  }
  const auto baked = BakeColorLook(look, lut);
  for (size_t row = 0; row < height; ++row) {
    auto* pixel = pixels.data() + row * stride;
    for (size_t column = 0; column < width; ++column, pixel += 4) {
      const float alpha = static_cast<float>(pixel[3]) / 255.0f;
      if (premultiplied && pixel[3] == 0) { pixel[0] = pixel[1] = pixel[2] = 0; continue; }
      Rgb original{};
      for (size_t channel = 0; channel < 3; ++channel) original[channel] = Clamp(pixel[channel] / (premultiplied ? 255.0f * alpha : 255.0f));
      const auto adjusted = SampleLut(baked, original);
      for (size_t channel = 0; channel < 3; ++channel) {
        pixel[channel] = static_cast<uint8_t>(std::lround(Clamp(adjusted[channel]) * (premultiplied ? alpha : 1.0f) * 255.0f));
      }
    }
  }
}

}  // namespace prooftray
