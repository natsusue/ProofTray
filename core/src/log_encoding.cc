#include "prooftray/log_encoding.h"

#include <algorithm>
#include <cmath>
#include <numbers>
#include <stdexcept>

namespace prooftray {
namespace {
using Vector = std::array<double, 3>;
using Matrix = std::array<Vector, 3>;
struct Xy {
  double x, y;
};
struct Primaries {
  Xy r, g, b;
};
constexpr Primaries k709{{.64, .33}, {.30, .60}, {.15, .06}};
constexpr Xy kD65{.3127, .3290};

constexpr double Dot(const Vector& a, const Vector& b) { return a[0] * b[0] + a[1] * b[1] + a[2] * b[2]; }
constexpr Vector Cross(const Vector& a, const Vector& b) { return {a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]}; }
constexpr Matrix Inverse(const Matrix& m) {
  const Matrix cofactors{Cross(m[1], m[2]), Cross(m[2], m[0]), Cross(m[0], m[1])};
  const double det = Dot(m[0], cofactors[0]);
  Matrix out{};
  for (int r = 0; r < 3; ++r)
    for (int c = 0; c < 3; ++c) out[r][c] = cofactors[c][r] / det;
  return out;
}

// RGB primary XYZ directions are columns. Scale them so unit RGB maps to D65 at Y=1 (SMPTE RP 177).
constexpr Matrix ToXyz(Primaries p) {
  Matrix m{{{p.r.x, p.g.x, p.b.x}, {p.r.y, p.g.y, p.b.y}, {1 - p.r.x - p.r.y, 1 - p.g.x - p.g.y, 1 - p.b.x - p.b.y}}};
  const Vector white{kD65.x / kD65.y, 1., (1 - kD65.x - kD65.y) / kD65.y};
  const Matrix inv = Inverse(m);
  for (int c = 0; c < 3; ++c) {
    const double scale = Dot(inv[c], white);
    for (auto& row : m) row[c] *= scale;
  }
  return m;
}

constexpr std::array<Rgb, 3> From709(Primaries target) {
  const Matrix inv = Inverse(ToXyz(target));
  constexpr Matrix source = ToXyz(k709);
  std::array<Rgb, 3> out{};
  for (int r = 0; r < 3; ++r)
    for (int c = 0; c < 3; ++c) {
      out[r][c] = static_cast<float>(Dot(inv[r], {source[0][c], source[1][c], source[2][c]}));
    }
  return out;
}

// ITU-R BT.709-6 (2015) primaries; scene-linear has an identity transfer.
double Linear(double x) { return x; }

// Sony, Technical Summary for S-Gamut3.Cine/S-Log3 and S-Gamut3/S-Log3, V1.00.
double SLog3(double x) { return (x < .01125 ? 95. + x * (171.2102946929 - 95.) / .01125 : 420. + 261.5 * std::log10((x + .01) / .19)) / 1023.; }

// Sony S-Log2: Khronos Data Format Specification v1.4.0 (2025), section 13.14.
// Reflectance -> scene-linear IRE uses /0.9; encoded IRE -> full-range 10-bit code uses 64 + 876*y.
double SLog2(double reflectance) {
  const double x = reflectance / .9;
  const double ire = x < 0. ? x * 3.53881278538813 + .030001222851889303
                           : .432699 * std::log10(x * 155. / 219. + .037584) + .616596 + .03;
  return (64. + 876. * ire) / 1023.;
}

// Nikon, N-Log Specification v1.0.0 (2018), normalized from the manufacturer's 10-bit code.
double NLog(double x) { return NLogCode(x) / 1023.; }

// RED, White Paper on REDWideGamutRGB and Log3G10, 915-0187 Rev B (2017).
double RedLog3G10(double x) {
  x += .01;
  return x < 0. ? 15.1927 * x : .224282 * std::log10(1. + 155.975327 * x);
}

// Panasonic, V-Log/V-Gamut Reference Manual Rev.1.0 (2014).
double VLog(double x) { return x < .01 ? 5.6 * x + .125 : .241514 * std::log10(x + .00873) + .598206; }

// Canon, Canon Log Gamma Curves (2018-11-01), Appendix [2], full range. Scene linear = reflection / 0.9.
double CLog2(double reflectance) {
  const double x = reflectance / .9;
  return .092864125 + std::copysign(.24136077 * std::log10(1. + 87.099375 * std::abs(x)), x);
}

// Canon, Canon Log Gamma Curves (2018-11-01), Appendix [3], full range.
double CLog3(double reflectance) {
  const double x = reflectance / .9;
  if (x < -.014) return .12783901 - .36726845 * std::log10(1. - 14.98325 * x);
  if (x <= .014) return .12512219 + 1.9754798 * x;
  return .12240537 + .36726845 * std::log10(1. + 14.98325 * x);
}

// DJI, White Paper on D-Log and D-Gamut of DJI Cinema Color System v1.0 (2017).
double DLog(double x) { return x <= .0078 ? 6.025 * x + .0929 : .256663 * std::log10(.9892 * x + .0108) + .584555; }

// ARRI, ALEXA Log C Curve - Usage in VFX (2017-03), SUP 3.x, scene exposure, EI 800.
double LogC3(double x) { return x > .010591 ? .247190 * std::log10(5.555556 * x + .052272) + .385537 : 5.367655 * x + .092809; }

// ARRI, LogC4 Specification (2025-01-23), section 4.1.1.
double LogC4(double x) {
  constexpr double a = (262144. - 16.) / 117.45, b = (1023. - 95.) / 1023., c = 95. / 1023.;
  static const double t = (std::exp2(6. - 14. * c / b) - 64.) / a;
  static const double s = 7. * std::numbers::ln2 * std::exp2(7. - 14. * c / b) / (a * b);
  return x < t ? (x - t) / s : (std::log2(a * x + 64.) - 6.) * b / 14. + c;
}

// Published CIE 1931 xy primaries, all D65. Canon Cinema Gamut: Wide Color Gamuts of the EOS C500.
constexpr Primaries kCinema{{.74, .27}, {.17, 1.14}, {.08, -.10}};
// S-Gamut and S-Gamut3 share nominal xy primaries: Sony Technical Summary V1.00, Color Primaries table.
constexpr Primaries kSGamut{{.730, .280}, {.140, .855}, {.100, -.050}};
constexpr LogEncodingSpec kEncodings[] = {
    {LutEncoding::Linear709, "Linear / Rec.709", From709(k709), Linear},
    {LutEncoding::SLog2, "S-Log2 / S-Gamut", From709(kSGamut), SLog2},
    {LutEncoding::SLog3Cine, "S-Log3 / S-Gamut3.Cine", From709({{.766, .275}, {.225, .800}, {.089, -.087}}), SLog3},
    {LutEncoding::SLog3, "S-Log3 / S-Gamut3", From709(kSGamut), SLog3},
    {LutEncoding::NLog, "N-Log / BT.2020", From709({{.708, .292}, {.170, .797}, {.131, .046}}), NLog},
    {LutEncoding::Log3G10, "RED Log3G10 / REDWideGamutRGB", From709({{.780308, .304253}, {.121595, 1.493994}, {.095612, -.084589}}), RedLog3G10},
    {LutEncoding::VLog, "V-Log / V-Gamut", From709({{.730, .280}, {.165, .840}, {.100, -.030}}), VLog},
    {LutEncoding::CLog2, "Canon Log 2 / Cinema Gamut", From709(kCinema), CLog2},
    {LutEncoding::CLog3, "Canon Log 3 / Cinema Gamut", From709(kCinema), CLog3},
    {LutEncoding::DLog, "D-Log / D-Gamut", From709({{.71, .31}, {.21, .88}, {.09, -.08}}), DLog},
    {LutEncoding::LogC3, "ARRI LogC3 EI 800 / AWG3", From709({{.684, .313}, {.221, .848}, {.0861, -.102}}), LogC3},
    {LutEncoding::LogC4, "ARRI LogC4 / AWG4", From709({{.7347, .2653}, {.1424, .8576}, {.0991, -.0308}}), LogC4},
};

float ToSrgb(float linear) {
  linear = std::clamp(linear, 0.f, 1.f);
  return linear <= .0031308f ? 12.92f * linear : 1.055f * std::pow(linear, 1.f / 2.4f) - .055f;
}
}  // namespace

// Nikon, N-Log Specification v1.0.0 (2018). Preserve the original black handling and 10-bit reference values.
double NLogCode(double reflectance) {
  if (!std::isfinite(reflectance)) throw std::invalid_argument("Non-finite scene reflectance");
  const double y = std::max(0.0, reflectance);
  return y < 0.328 ? 650.0 * std::cbrt(y + 0.0075) : 150.0 * std::log(y) + 619.0;
}

const LogEncodingSpec& GetLogEncoding(LutEncoding encoding) {
  for (const auto& spec : kEncodings)
    if (spec.encoding == encoding) return spec;
  throw std::invalid_argument("Unsupported scene-linear LUT encoding");
}

Rgb LogEncodingSpec::ToGamut(const Rgb& rgb) const {
  if (encoding == LutEncoding::Linear709) return rgb;
  Rgb out{};
  for (int i = 0; i < 3; ++i) out[i] = from_rec709[i][0] * rgb[0] + from_rec709[i][1] * rgb[1] + from_rec709[i][2] * rgb[2];
  return out;
}

double LogCoordinate(double full, LogRange range) { return range == LogRange::Full10 ? full : (full * 1023. - 64.) / 876.; }

LogGrade PrepareLogGrade(LogRange range, float k, const ColorLook& look) {
  ValidateLookParameters(look);
  if ((look.output_encoding != LutEncoding::Rec709Gamma24 && look.output_encoding != LutEncoding::Direct) ||
      !std::isfinite(k) || k <= 0) throw std::invalid_argument("Invalid Log grade");
  const float gain = k * std::exp2(look.exposure_ev), warm = std::exp2(look.warmth * .25f);
  const float tint = std::exp2(-look.tint * .25f), exposure = std::exp2(look.exposure_ev);
  return {GetLogEncoding(look.input_encoding), range, {gain * warm, gain * tint, gain / warm}, look.strength,
          std::nullopt, look.output_encoding,
          {exposure * warm, exposure * tint, exposure / warm}, DisplayAdjustmentsTable(look)};
}

Rgb EncodeLogPixel(const Rgb& linear_srgb, const LogGrade& grade, LogRange range) {
  Rgb input = linear_srgb;
  for (int i = 0; i < 3; ++i) input[i] *= grade.gain[i];
  input = grade.spec.ToGamut(input);
  for (float& c : input) c = static_cast<float>(LogCoordinate(grade.spec.encode(c), range));
  return input;
}

Rgb RenderLogPixel(const Rgb& linear_srgb, const ColorLut& lut, const LogGrade& grade) {
  Rgb base{};
  if (grade.strength != 1) {
    auto input = linear_srgb;
    for (int i = 0; i < 3; ++i) input[i] *= grade.base_gain[i];
    if (grade.base_render) base = RenderRawDefault(input, *grade.base_render);
    else for (int i = 0; i < 3; ++i) base[i] = ToSrgb(input[i]);
  }
  if (grade.strength == 0) return grade.display_edits ? SampleLut(*grade.display_edits, base) : base;
  auto graded = SampleLut(lut, EncodeLogPixel(linear_srgb, grade, grade.range));
  for (float& c : graded) {
    c = grade.output_encoding == LutEncoding::Direct ? std::clamp(c, 0.f, 1.f) : ToSrgb(std::pow(std::max(0.f, c), 2.4f));
  }
  for (int i = 0; i < 3; ++i) base[i] = std::lerp(base[i], graded[i], grade.strength);
  return grade.display_edits ? SampleLut(*grade.display_edits, base) : base;
}
}  // namespace prooftray
