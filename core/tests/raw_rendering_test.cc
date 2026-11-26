#include "prooftray/raw_exposure.h"
#include "prooftray/raw_rendering.h"

#include <cmath>
#include <iostream>
#include <limits>
#include <stdexcept>

namespace {
void Check(bool value, const char* message) { if (!value) throw std::runtime_error(message); }
void Near(const prooftray::Rgb& a, const prooftray::Rgb& b) {
  for (int i = 0; i < 3; ++i) Check(std::isfinite(a[i]) && std::abs(a[i] - b[i]) < 2e-5f, "RAW rendering mismatch");
}
template <typename F> void Reject(F function) {
  try { function(); } catch (const std::invalid_argument&) { return; }
  throw std::runtime_error("Invalid RAW setting was accepted");
}

void ReferenceVectors() {
  using namespace prooftray;
  struct Vector { Rgb input; float baseline; Rgb expected; };
  // Independent double-precision fixtures from the pinned SDK SDR equations, including its negative-EV shoulder and D50 matrices.
  const Vector vectors[]{
    {{0.081000000f, 0.081000000f, 0.081000000f}, 0.0f, {0.405069463f, 0.405069463f, 0.405069463f}},
    {{0.180000000f, 0.180000000f, 0.180000000f}, 0.0f, {0.649354765f, 0.649354765f, 0.649354765f}},
    {{0.040000000f, 0.080000000f, 0.300000000f}, 0.0f, {0.241790914f, 0.402396813f, 0.805107416f}},
    {{0.040000000f, 0.080000000f, 0.300000000f}, 1.0f, {0.509864327f, 0.608805712f, 0.959310272f}},
    {{0.040000000f, 0.080000000f, 0.300000000f}, -1.0f, {0.044260732f, 0.251908135f, 0.598471768f}},
    {{0.040000000f, 0.080000000f, 0.300000000f}, 0.5f, {0.372979636f, 0.501392097f, 0.890498108f}},
    {{-0.040000000f, 0.100000000f, 0.400000000f}, 0.0f, {0.000000000f, 0.446944043f, 0.880460628f}},
    {{2.000000000f, 0.500000000f, 0.100000000f}, 0.0f, {1.000000000f, 0.833380533f, 0.600175541f}},
    {{0.900000000f, 0.800000000f, 0.600000000f}, -3.0f, {0.994691624f, 0.927790923f, 0.770270852f}},
  };
  for (const auto& v : vectors) Near(RenderRawDefault(v.input, PrepareRawDefault({v.baseline})), v.expected);
  const auto standard = PrepareRawDefault();
  Near(RenderRawDefault({0, 0, 0}, standard), {0, 0, 0});
  Near(RenderRawDefault({1, 1, 1}, standard), {1, 1, 1});
  float previous = 0;
  for (int i = 0; i <= 1000; ++i) {
    const float x = i / 1000.f;
    const auto rgb = RenderRawDefault({x, x, x}, standard);
    Check(rgb[0] + 1e-6f >= previous && rgb[0] >= 0 && rgb[0] <= 1, "Non-monotone default gray");
    Near(rgb, {rgb[0], rgb[0], rgb[0]});
    previous = rgb[0];
  }
  for (const auto& input : {Rgb{-.1f, .5f, 2.f}, Rgb{2.f, 3.f, 4.f}, Rgb{1e-30f, 1e-30f, 1e-30f}}) {
    for (float c : RenderRawDefault(input, standard)) Check(std::isfinite(c) && c >= 0 && c <= 1, "Invalid display pixel");
  }
  const float nan = std::numeric_limits<float>::quiet_NaN();
  Reject([&] { PrepareRawDefault({nan}); });
  Reject([&] { PrepareRawDefault({17.f}); });
  Reject([&] { RenderRawDefault({nan, 0, 0}, standard); });
}

void DisplayAndLogEndpoints() {
  using namespace prooftray;
  const RawRenderSettings settings{.5f};
  ColorLook look;
  look.exposure_ev = 1;
  const Rgb raw{.04f, .06f, .08f};
  const Rgb linear_exposed{raw[0] * 2, raw[1] * 2, raw[2] * 2};
  const auto exposed = RenderRawDefault(linear_exposed, PrepareRawDefault(settings));
  Near(RenderRawDisplayPixel(raw, nullptr, PrepareRawDisplayGrade(settings, look)), exposed);
  look.strength = 0;
  Near(RenderRawDisplayPixel(raw, nullptr, PrepareRawDisplayGrade(settings, look)), exposed);

  const auto lut = ParseCube("LUT_1D_SIZE 2\n0 0 0\n1 1 1\n");
  look.input_encoding = LutEncoding::NLog;
  for (auto output : {LutEncoding::Direct, LutEncoding::Rec709Gamma24}) {
    look.output_encoding = output;
    look.strength = 0;
    Near(RenderLogPixel(raw, lut, PrepareRawLogGrade(LogRange::Full10, 2.f, look, kGenericRawExposureReference, settings)), exposed);
    look.strength = 1;
    const auto with_display_ev = PrepareRawLogGrade(LogRange::Full10, 2.f, look, kGenericRawExposureReference, settings);
    const auto without_display_ev = PrepareRawLogGrade(LogRange::Full10, 2.f, look);
    Near(RenderLogPixel(raw, lut, with_display_ev), RenderLogPixel(raw, lut, without_display_ev));
    const auto graded = RenderLogPixel(raw, lut, with_display_ev);
    look.strength = .5f;
    Rgb mixed{};
    for (int i = 0; i < 3; ++i) mixed[i] = (exposed[i] + graded[i]) / 2;
    Near(RenderLogPixel(raw, lut, PrepareRawLogGrade(LogRange::Full10, 2.f, look, kGenericRawExposureReference, settings)), mixed);
  }
}

void OutputEncoding() {
  using namespace prooftray;
  const auto lut = ParseCube("LUT_1D_SIZE 2\n0.1 0.4 0.5\n0.1 0.4 0.5\n");
  ColorLook look;
  look.input_encoding = LutEncoding::SLog3Cine;
  look.output_encoding = LutEncoding::Direct;
  Near(RenderLogPixel({.18f, .18f, .18f}, lut, PrepareLogGrade(LogRange::Full10, 1.f, look)), {.1f, .4f, .5f});
  look.output_encoding = LutEncoding::Rec709Gamma24;
  Near(RenderLogPixel({.18f, .18f, .18f}, lut, PrepareLogGrade(LogRange::Full10, 1.f, look)), {.0505f, .367f, .4725f});
  look.output_encoding = LutEncoding::NLog;
  Reject([&] { PrepareLogGrade(LogRange::Full10, 1.f, look); });
}
}  // namespace

int main() {
  try {
    ReferenceVectors();
    DisplayAndLogEndpoints();
    OutputEncoding();
    std::cout << "RAW standard rendering tests passed\n";
    return 0;
  } catch (const std::exception& error) { std::cerr << error.what() << '\n'; return 1; }
}
