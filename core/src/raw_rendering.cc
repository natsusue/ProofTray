// DNG SDK reference rendering portions Copyright 2006-2023 Adobe Systems Incorporated. All Rights Reserved.
// Adobe permits use, modification and distribution under the DNG SDK License Agreement.
// See licenses/Adobe-DNG-SDK.txt and THIRD_PARTY_NOTICES.md for the license, source revision and adaptation scope.

#include "prooftray/raw_rendering.h"

#include <algorithm>
#include <array>
#include <cmath>
#include <stdexcept>

namespace prooftray {
namespace {
using Vector = std::array<double, 3>;
using Matrix = std::array<Vector, 3>;

constexpr double Dot(const Vector& a, const Vector& b) { return a[0] * b[0] + a[1] * b[1] + a[2] * b[2]; }
constexpr Vector Cross(const Vector& a, const Vector& b) {
  return {a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]};
}
constexpr Matrix Inverse(const Matrix& matrix) {
  const Matrix cofactors{Cross(matrix[1], matrix[2]), Cross(matrix[2], matrix[0]), Cross(matrix[0], matrix[1])};
  const double determinant = Dot(matrix[0], cofactors[0]);
  Matrix result{};
  for (int row = 0; row < 3; ++row)
    for (int col = 0; col < 3; ++col) result[row][col] = cofactors[col][row] / determinant;
  return result;
}
constexpr Matrix Multiply(const Matrix& a, const Matrix& b) {
  Matrix result{};
  for (int row = 0; row < 3; ++row)
    for (int col = 0; col < 3; ++col) result[row][col] = Dot(a[row], {b[0][col], b[1][col], b[2][col]});
  return result;
}

// dng_color_space::SetMatrixToPCS normalizes rounded matrix rows to the same D50 white.
constexpr Matrix ToPcs(Matrix matrix) {
  constexpr Vector white{.3457 / .3585, 1., (1. - .3457 - .3585) / .3585};
  for (int row = 0; row < 3; ++row) {
    const double scale = white[row] / (matrix[row][0] + matrix[row][1] + matrix[row][2]);
    for (double& value : matrix[row]) value *= scale;
  }
  return matrix;
}
constexpr auto kSrgbToPcs = ToPcs({{{.4361, .3851, .1431}, {.2225, .7169, .0606}, {.0139, .0971, .7141}}});
constexpr auto kProPhotoToPcs = ToPcs({{{.7977, .1352, .0313}, {.2880, .7119, .0001}, {0., 0., .8249}}});
constexpr auto kToWorking = Multiply(Inverse(kProPhotoToPcs), kSrgbToPcs);
constexpr auto kToOutput = Multiply(Inverse(kSrgbToPcs), kProPhotoToPcs);

constexpr std::array<float, 1025> kAcr3Default{
#include "dng_default_curve.inc"
};
static_assert(kAcr3Default.front() == 0 && kAcr3Default.back() == 1 && std::is_sorted(kAcr3Default.begin(), kAcr3Default.end()));

Rgb Convert(const Rgb& color, const Matrix& matrix) {
  Rgb result{};
  for (int row = 0; row < 3; ++row) result[row] = float(Dot(matrix[row], {color[0], color[1], color[2]}));
  return result;
}

float Encode(float value) {
  value = std::clamp(value, 0.f, 1.f);
  return value <= .0031308f ? value * 12.92f : 1.055f * std::pow(value, 1.f / 2.4f) - .055f;
}

float ExposureRamp(float x, const RawDisplayTransform& transform) {
  if (x <= transform.black - transform.radius) return 0;
  if (x >= transform.black + transform.radius) return std::min((x - transform.black) * transform.slope, 1.f);
  x -= transform.black - transform.radius;
  return transform.quadratic * x * x;
}

float Tone(float x, const RawDisplayTransform& transform) {
  // dng_1d_concatenate evaluates exposure_tone first, then the baseline curve. Negative EV preserves reference white.
  if (transform.negative_slope < 1) {
    x = x <= .25f ? x * transform.negative_slope : (transform.negative_a * x + transform.negative_b) * x + transform.negative_c;
  }
  const float position = std::clamp(x, 0.f, 1.f) * (kAcr3Default.size() - 1);
  const size_t index = std::min(size_t(position), kAcr3Default.size() - 2);
  return std::lerp(kAcr3Default[index], kAcr3Default[index + 1], position - index);
}
}  // namespace

RawDisplayTransform PrepareRawDefault(const RawRenderSettings& settings) {
  const float exposure = settings.baseline_exposure_ev;
  if (!std::isfinite(exposure) || std::abs(exposure) > 16) throw std::invalid_argument("Invalid RAW display exposure");
  const float white = std::exp2(-std::max(exposure, 0.f));
  // SDK defaults: Shadows=5, ShadowScale=1. No profile DefaultBlackRender override or baked Stage3 exposure is present.
  const float black = std::min(.005f, .99f * white);
  const float slope = 1.f / (white - black);
  const float radius = std::min(.5f * black, (1.f / 16.f) / slope);
  const float negative_slope = std::exp2(std::min(exposure, 0.f));
  const float a = (16.f / 9.f) * (1.f - negative_slope);
  const float b = negative_slope - .5f * a;
  return {black, radius, slope, radius > 0 ? slope / (4.f * radius) : 0.f, negative_slope, a, b, 1.f - a - b};
}

Rgb RenderRawDefault(const Rgb& input, const RawDisplayTransform& transform) {
  for (float c : input) if (!std::isfinite(c)) throw std::invalid_argument("Non-finite RAW pixel");
  // Preserve signed sRGB intermediates until after conversion; the SDK SDR reference clips in its wider working space.
  auto working = Convert(input, kToWorking);
  for (float& c : working) c = ExposureRamp(std::clamp(c, 0.f, 1.f), transform);
  const float low = std::min({working[0], working[1], working[2]});
  const float high = std::max({working[0], working[1], working[2]});
  const float mapped_low = Tone(low, transform), mapped_high = Tone(high, transform);
  // RefBaselineRGBTone maps extremes and keeps the middle component's relative position, not the RGB ratios to luminance.
  for (float& c : working) c = high > low ? mapped_low + (mapped_high - mapped_low) * ((c - low) / (high - low)) : mapped_low;
  auto result = Convert(working, kToOutput);
  for (float& c : result) c = Encode(c);
  return result;
}

RawDisplayGrade PrepareRawDisplayGrade(const RawRenderSettings& settings, const ColorLook& look) {
  ValidateLook(look);
  const float exposure = std::exp2(look.exposure_ev);
  const Rgb gain{exposure * std::exp2(look.warmth * .25f), exposure * std::exp2(-look.tint * .25f), exposure * std::exp2(-look.warmth * .25f)};
  return {PrepareRawDefault(settings), gain, look.strength, look.input_encoding, look.output_encoding, DisplayAdjustmentsTable(look)};
}

Rgb RenderRawDisplayPixel(const Rgb& linear, const ColorLut* lut, const RawDisplayGrade& grade) {
  auto input = linear;
  for (int c = 0; c < 3; ++c) input[c] *= grade.gain[c];
  const auto display = ApplyDisplayLut(RenderRawDefault(input, grade.transform), lut, grade.lut_strength, grade.lut_input, grade.lut_output);
  return grade.display_edits ? SampleLut(*grade.display_edits, display) : display;
}

}  // namespace prooftray
