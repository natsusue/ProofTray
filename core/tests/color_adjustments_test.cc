#include "prooftray/color_lut.h"
#include "prooftray/raw_exposure.h"
#include "prooftray/raw_rendering.h"

#include <algorithm>
#include <array>
#include <cmath>
#include <iostream>
#include <limits>
#include <stdexcept>

namespace {
using namespace prooftray;

void Check(bool value, const char* message) { if (!value) throw std::runtime_error(message); }
void Near(const Rgb& a, const Rgb& b, float tolerance = 1e-5f) {
  for (int c = 0; c < 3; ++c) Check(std::abs(a[c] - b[c]) <= tolerance, "Color edit result differs");
}
template <typename Work> void Reject(Work work) {
  try { work(); } catch (const std::invalid_argument&) { return; }
  throw std::runtime_error("Invalid edits were accepted");
}

void LutStrength() {
  const auto inverse = ParseCube("LUT_1D_SIZE 2\n1 1 1\n0 0 0\n");
  ColorLook look;
  look.strength = 0;
  look.exposure_ev = 1;
  const Rgb srgb{.2f, .3f, .4f};
  const auto exposed = ApplyColorLook(srgb, look, nullptr);
  Check(exposed[1] > srgb[1], "Zero LUT strength disabled exposure");
  Near(ApplyColorLook(srgb, look, &inverse), exposed);
  look.adjustments.shadows = .4f;
  look.adjustments.curves[0] = {{0, 0}, {.4f, .5f}, {1, 1}};
  Near(ApplyColorLook(srgb, look, &inverse), ApplyColorLook(srgb, look, nullptr));

  const Rgb raw{.1f, .15f, .2f};
  ColorLook identity;
  Near(RenderRawDisplayPixel(raw, nullptr, PrepareRawDisplayGrade({}, identity)), RenderRawDefault(raw, PrepareRawDefault()));
  auto dim = identity;
  dim.exposure_ev = -2;
  const auto darker = RenderRawDisplayPixel({.9f, .9f, .9f}, nullptr, PrepareRawDisplayGrade({}, dim));
  const auto neutral = RenderRawDisplayPixel({.9f, .9f, .9f}, nullptr, PrepareRawDisplayGrade({}, identity));
  Check(darker[0] < neutral[0] - .1f, "Negative exposure must lower highlights");

  look.input_encoding = LutEncoding::SLog3Cine;
  look.output_encoding = LutEncoding::Direct;
  const auto log = RenderLogPixel(raw, inverse, PrepareRawLogGrade(LogRange::Full10, 2.f, look));
  auto display = look;
  display.input_encoding = LutEncoding::Direct;
  Near(log, RenderRawDisplayPixel(raw, nullptr, PrepareRawDisplayGrade({}, display)));
}

void CurvesAndHueBands() {
  const ToneCurve curve{{0, 0}, {.1f, .3f}, {.7f, .8f}, {1, 1}};
  for (auto point : curve) Check(std::abs(EvaluateToneCurve(curve, point.x) - point.y) < 1e-6f, "Curve misses its control point");
  float previous = 0;
  for (int i = 0; i <= 1000; ++i) {
    const float y = EvaluateToneCurve(curve, i / 1000.f);
    Check(y >= previous - 1e-6f && y >= 0 && y <= 1, "Monotone curve overshoots");
    previous = y;
  }
  ColorLook look;
  look.adjustments.hsl[0][0] = 1;
  Near(ApplyDisplayAdjustments({1, 0, 0}, look), {1, .5f, 0});
  Near(ApplyDisplayAdjustments({.5f, .5f, .5f}, look), {.5f, .5f, .5f});
  Near(ApplyDisplayAdjustments({0, 0, 1}, look), {0, 0, 1});
  look.adjustments.hsl[0] = {0, -1, 0};
  Near(ApplyDisplayAdjustments({1, 0, 0}, look), {.5f, .5f, .5f});
  look.adjustments = {};
  look.adjustments.grading[0] = {220, .5f, 0};
  const auto tinted = ApplyDisplayAdjustments({.2f, .2f, .2f}, look);
  Check(tinted[2] > tinted[0], "Blue shadow grading has no effect");
  Near(ApplyDisplayAdjustments({0, 0, 0}, look), {0, 0, 0});
  Near(ApplyDisplayAdjustments({1, 1, 1}, look), {1, 1, 1});
  look.adjustments.grading[2] = {60, .5f, 0};
  look.adjustments.balance = -1;
  const auto shadows = ApplyDisplayAdjustments({.5f, .5f, .5f}, look);
  look.adjustments.balance = 1;
  Check(ApplyDisplayAdjustments({.5f, .5f, .5f}, look)[0] > shadows[0], "Positive balance must favor highlight grading");
}

void BakeAndAlpha() {
  ColorLook look;
  look.exposure_ev = .2f;
  look.tint = .1f;
  look.adjustments.curves[0] = {{0, 0}, {.3f, .35f}, {.7f, .75f}, {1, 1}};
  look.adjustments.vibrance = .1f;
  const auto baked = BakeColorLook(look, nullptr);
  Check(baked.size == 65 && baked.values.size() == 65 * 65 * 65 * 3, "Unexpected display table size");
  for (int i = 0; i < 300; ++i) {
    const Rgb input{(i * 17 % 299) / 299.f, (i * 37 % 299) / 299.f, (i * 71 % 299) / 299.f};
    Near(SampleLut(baked, input), ApplyColorLook(input, look, nullptr), 3.f / 255.f);
  }
  const auto table = DisplayAdjustmentsTable(look);
  auto other_exposure = look;
  other_exposure.exposure_ev = 2;
  Check(table == DisplayAdjustmentsTable(other_exposure), "Scene exposure should not invalidate the display-edit table");
  std::array<uint8_t, 12> image{40, 60, 80, 128, 0, 0, 0, 0, 99, 98, 97, 96};
  const auto expected = SampleLut(baked, {40.f / 128.f, 60.f / 128.f, 80.f / 128.f});
  ApplyColorLookRgba(image, 2, 1, 12, true, look, nullptr);
  for (int c = 0; c < 3; ++c) Check(image[c] == uint8_t(std::lround(expected[c] * 128.f)), "CPU output differs from the preview table");
  Check(image[3] == 128 && image[4] == 0 && image[7] == 0 && image[8] == 99, "Alpha or row padding changed");
}

void GradingRangeMasks() {
  Near(ColorGradingWeights(.2f, .5f, 0), {1, 0, 0});
  Near(ColorGradingWeights(.5f, .5f, 0), {0, 1, 0});
  Near(ColorGradingWeights(.8f, .5f, 0), {0, 0, 1});
  for (float blending : {0.f, .5f, 1.f}) {
    for (float balance : {-1.f, 0.f, 1.f}) {
      for (int i = 0; i <= 1000; ++i) {
        const auto weights = ColorGradingWeights(i / 1000.f, blending, balance);
        for (float value : weights) Check(std::isfinite(value) && value >= 0 && value <= 1, "Invalid tonal mask weight");
        Check(std::abs(weights[0] + weights[1] + weights[2] - 1) < 1e-6f, "Tonal masks amplify or lose total strength");
      }
    }
  }
  const auto wide = ColorGradingWeights(.5f, 1, 0);
  Check(wide[0] > 0 && wide[2] > 0 && wide[1] < 1, "Blending must widen overlap");
  Check(ColorGradingWeights(.5f, .5f, 1)[2] > .9f, "Positive balance must favor highlights");
  Check(ColorGradingWeights(.5f, .5f, -1)[0] > .9f, "Negative balance must favor shadows");
  Reject([] { ColorGradingWeights(.5f, std::numeric_limits<float>::quiet_NaN(), 0); });
}

void GradingIsolationAndOverlap() {
  const std::array<float, 3> gray{.2f, .5f, .8f};
  for (int wheel = 0; wheel < 4; ++wheel) {
    ColorLook look;
    look.adjustments.grading[wheel] = {220, .5f, 0};
    const auto baked = BakeColorLook(look, nullptr);
    for (int region = 0; region < 3; ++region) {
      const Rgb input{gray[region], gray[region], gray[region]};
      const auto direct = ApplyDisplayAdjustments(input, look);
      const auto sampled = SampleLut(baked, input);
      Near(sampled, direct, 1.f / 255.f);
      if (wheel == region || wheel == 3) Check(direct[2] - direct[0] > .02f, "Selected tonal range was not graded");
      else Near(direct, input);
      if (wheel != region && wheel != 3) Near(sampled, input);
    }
    look.adjustments.grading[wheel] = {0, 0, .5f};
    for (int region = 0; region < 3; ++region) {
      const Rgb input{gray[region], gray[region], gray[region]};
      const auto result = ApplyDisplayAdjustments(input, look);
      if (wheel == region || wheel == 3) Check(result[0] > input[0] + .02f, "Selected range luminance was not adjusted");
      else Near(result, input);
    }
  }

  ColorLook all_ranges, global;
  for (int wheel = 0; wheel < 3; ++wheel) all_ranges.adjustments.grading[wheel] = {210, .3f, .15f};
  global.adjustments.grading[3] = {210, .3f, .15f};
  for (float blending : {0.f, .5f, 1.f}) {
    all_ranges.adjustments.blending = blending;
    for (float balance : {-1.f, 0.f, 1.f}) {
      all_ranges.adjustments.balance = balance;
      for (int i = 1; i < 100; ++i) {
        const float level = i / 100.f;
        const Rgb input{level, level * .8f, level * .6f};
        Near(ApplyDisplayAdjustments(input, all_ranges), ApplyDisplayAdjustments(input, global));
      }
    }
  }
  const auto reference = ApplyDisplayAdjustments({.4f, .4f, .4f}, global);
  global.adjustments.blending = 1;
  global.adjustments.balance = -1;
  Near(ApplyDisplayAdjustments({.4f, .4f, .4f}, global), reference);
}

void ValidateWire() {
  std::vector<float> packed(43);
  packed[5] = .5f;
  for (int i = 0; i < 4; ++i) packed.insert(packed.end(), {2, 0, 0, 1, 1});
  Check(ParseColorAdjustments(packed).IsIdentity(), "Neutral wire format is not identity");
  packed[43] = 2.5f;
  Reject([&] { ParseColorAdjustments(packed); });
  packed[43] = 2;
  packed[0] = std::numeric_limits<float>::quiet_NaN();
  Reject([&] { ParseColorAdjustments(packed); });
  ColorLook invalid;
  invalid.tint = 2.f;
  Reject([&] { ValidateLookParameters(invalid); });
}
}  // namespace

int main() {
  try {
    LutStrength(); CurvesAndHueBands(); BakeAndAlpha(); GradingRangeMasks(); GradingIsolationAndOverlap(); ValidateWire();
    std::cout << "Advanced color tests passed\n";
    return 0;
  } catch (const std::exception& error) { std::cerr << error.what() << '\n'; return 1; }
}
