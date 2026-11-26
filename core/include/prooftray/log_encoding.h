#ifndef PROOFTRAY_LOG_ENCODING_H_
#define PROOFTRAY_LOG_ENCODING_H_

#include <optional>

#include "prooftray/color_lut.h"
#include "prooftray/raw_rendering.h"

namespace prooftray {

enum class LogRange { Full10, Video10 };

struct LogEncodingSpec {
  LutEncoding encoding;
  std::string_view name;
  std::array<Rgb, 3> from_rec709;
  double (*encode)(double);  // Scene reflectance to normalized full-range code, before the LUT domain.
  Rgb ToGamut(const Rgb& linear_srgb) const;
};

const LogEncodingSpec& GetLogEncoding(LutEncoding encoding);
double LogCoordinate(double full, LogRange range);

struct LogGrade {
  const LogEncodingSpec& spec;
  LogRange range;
  Rgb gain;
  float strength;
  std::optional<RawDisplayTransform> base_render = std::nullopt;
  LutEncoding output_encoding = LutEncoding::Rec709Gamma24;
  Rgb base_gain{1, 1, 1};
  std::shared_ptr<const ColorLut> display_edits;
};

LogGrade PrepareLogGrade(LogRange range, float k, const ColorLook& look);
// Full10 returns normalized Log code values, with no LUT, display transform or integer quantization.
// Video10 is a LUT coordinate convention; it is not the storage range of a Log TIFF.
Rgb EncodeLogPixel(const Rgb& linear_srgb, const LogGrade& grade, LogRange range = LogRange::Full10);
Rgb RenderLogPixel(const Rgb& linear_srgb, const ColorLut& lut, const LogGrade& grade);

// Nikon reference probe uses the unquantized 10-bit code.
double NLogCode(double reflectance);

}  // namespace prooftray
#endif  // PROOFTRAY_LOG_ENCODING_H_
