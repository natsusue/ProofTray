#ifndef PROOFTRAY_RAW_EXPOSURE_H_
#define PROOFTRAY_RAW_EXPOSURE_H_

#include <cmath>
#include <stdexcept>
#include <string_view>

#include "prooftray/log_encoding.h"

namespace prooftray {

struct RawExposureReference {
  float scale;
  std::string_view source;
  bool calibrated;
};

// OIIO raw.imageio/rawinput.cpp's generic camera-to-scene reference, measured there on a Canon 7D.
// This is an explicitly uncalibrated fallback, not a Nikon/Sony profile. No frame statistics or EXIF exposure bias are used.
// https://github.com/AcademySoftwareFoundation/OpenImageIO/blob/main/src/raw.imageio/rawinput.cpp
inline constexpr RawExposureReference kGenericRawExposureReference{1.f / .45f, "oiio-generic", false};

// Keep buffers unscaled. The scene reference and user K apply only to the technical LUT branch.
// The neutral endpoint uses the standard display transform with basic edits, without reference/K gain.
inline LogGrade PrepareRawLogGrade(LogRange range, float user_gain_ev, const ColorLook& look,
                                  const RawExposureReference& reference = kGenericRawExposureReference,
                                  const RawRenderSettings& settings = {}) {
  if (!std::isfinite(reference.scale) || reference.scale <= 0 || !std::isfinite(user_gain_ev) || std::abs(user_gain_ev) > 3) {
    throw std::invalid_argument("Invalid RAW exposure reference or user gain");
  }
  auto grade = PrepareLogGrade(range, reference.scale * std::exp2(user_gain_ev), look);
  grade.base_render = PrepareRawDefault(settings);
  return grade;
}

}  // namespace prooftray
#endif  // PROOFTRAY_RAW_EXPOSURE_H_
