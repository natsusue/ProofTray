#ifndef PROOFTRAY_RAW_RENDERING_H_
#define PROOFTRAY_RAW_RENDERING_H_

#include "prooftray/color_lut.h"

namespace prooftray {

// LibRaw has already subtracted black, normalized nominal white, applied camera WB and the camera matrix.
// There is no DNG Stage3 gain in this adapter. Display exposure never becomes the technical LUT's scene reference.
struct RawRenderSettings {
  float baseline_exposure_ev = 0;
};

struct RawDisplayTransform {
  float black, radius, slope, quadratic;
  float negative_slope, negative_a, negative_b, negative_c;
};

struct RawDisplayGrade {
  RawDisplayTransform transform;
  Rgb gain;
  float lut_strength;
  LutEncoding lut_input, lut_output;
  std::shared_ptr<const ColorLut> display_edits;
};

// DNG SDK SDR reference: linear ProPhoto D50, exposure/shadow ramp, ACR3 RGB tone, final sRGB conversion/encoding.
RawDisplayTransform PrepareRawDefault(const RawRenderSettings& settings = {});
Rgb RenderRawDefault(const Rgb& linear_srgb, const RawDisplayTransform& transform);
RawDisplayGrade PrepareRawDisplayGrade(const RawRenderSettings& settings, const ColorLook& look);
Rgb RenderRawDisplayPixel(const Rgb& linear_srgb, const ColorLut* lut, const RawDisplayGrade& grade);

}  // namespace prooftray
#endif  // PROOFTRAY_RAW_RENDERING_H_
