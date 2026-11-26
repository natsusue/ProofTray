#ifndef PROOFTRAY_COLOR_LUT_H_
#define PROOFTRAY_COLOR_LUT_H_

#include <array>
#include <cstddef>
#include <cstdint>
#include <memory>
#include <span>
#include <string>
#include <string_view>
#include <vector>

#include "prooftray/color_adjustments.h"

namespace prooftray {

using Rgb = std::array<float, 3>;

struct ColorLut {
  int dimension = 3;
  int size = 0;
  Rgb domain_min{0, 0, 0};
  Rgb domain_max{1, 1, 1};
  // Red changes fastest in 3D tables. 1D entries hold independent R/G/B curves.
  std::vector<float> values;
  std::string title;
  bool domain_declared = false;  // Import metadata only; not part of the canonical table or its content hash.
};

// Values are shared with the Kotlin LutEncoding and the preview shader.
enum class LutEncoding {
  Direct = 0, Rec709Gamma24 = 2, NLog = 3, Linear709 = 4, SLog3Cine = 5, SLog3 = 6, Log3G10 = 7, VLog = 8,
  CLog2 = 9, CLog3 = 10, DLog = 11, LogC3 = 12, LogC4 = 13, SLog2 = 14
};

struct ColorLook {
  float strength = 1;
  float exposure_ev = 0;
  float contrast = 0;
  float saturation = 1;
  float warmth = 0;
  LutEncoding input_encoding = LutEncoding::Direct;
  LutEncoding output_encoding = LutEncoding::Direct;
  float tint = 0;
  ColorAdjustments adjustments;
};

ColorLut ParseCube(std::string_view source);
ColorLut ParseLutBinary(std::span<const uint8_t> source);
std::vector<uint8_t> SerializeLut(const ColorLut& lut);
ColorLut FromHaldRgba(std::span<const uint8_t> pixels, size_t side, size_t stride);
void ValidateLut(const ColorLut& lut);
void ValidateLookParameters(const ColorLook& look);
void ValidateLook(const ColorLook& look);
// Per-pixel helpers require validated LUTs/looks. SampleLut clamps finite input to the LUT domain.
Rgb SampleLut(const ColorLut& lut, const Rgb& input);
// The input to ApplyColorLook must be finite, normalized sRGB.
Rgb ApplyColorLook(const Rgb& srgb, const ColorLook& look, const ColorLut* lut);
Rgb ApplyDisplayLut(const Rgb& srgb, const ColorLut* lut, float strength, LutEncoding input, LutEncoding output);
// Display edits are baked at 65^3. RAW and Log share this post-LUT table; JPEG bakes the whole point pipeline.
Rgb ApplyDisplayAdjustments(const Rgb& srgb, const ColorLook& look);
std::shared_ptr<const ColorLut> DisplayAdjustmentsTable(const ColorLook& look);
ColorLut BakeColorLook(const ColorLook& look, const ColorLut* lut);
// Mutable SDR sRGB RGBA8 pixels. Alpha is preserved; premultiplied buffers are unpremultiplied for grading.
void ApplyColorLookRgba(std::span<uint8_t> pixels, size_t width, size_t height, size_t stride, bool premultiplied,
                        const ColorLook& look, const ColorLut* lut);

}  // namespace prooftray
#endif  // PROOFTRAY_COLOR_LUT_H_
