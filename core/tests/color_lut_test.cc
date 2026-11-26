#include "prooftray/color_histogram.h"
#include "prooftray/color_lut.h"
#include "prooftray/log_encoding.h"
#include "prooftray/raw_exposure.h"

#include <algorithm>
#include <array>
#include <cmath>
#include <iostream>
#include <limits>
#include <stdexcept>
#include <string>
#include <vector>

namespace {

void Check(bool condition, const char* message) { if (!condition) throw std::runtime_error(message); }

void Near(const prooftray::Rgb& actual, const prooftray::Rgb& expected, float tolerance = 0.00001f) {
  for (size_t channel = 0; channel < 3; ++channel) {
    Check(std::isfinite(actual[channel]) && std::abs(actual[channel] - expected[channel]) <= tolerance, "Unexpected interpolated color");
  }
}

template <typename Operation>
void Reject(Operation operation) {
  bool rejected = false;
  try { operation(); } catch (const std::invalid_argument&) { rejected = true; }
  Check(rejected, "Invalid color input was accepted");
}

constexpr auto kIdentity = "LUT_3D_SIZE 2\n0 0 0\n1 0 0\n0 1 0\n1 1 0\n0 0 1\n1 0 1\n0 1 1\n1 1 1\n";

void CubeAndInterpolation() {
  const auto identity = prooftray::ParseCube(std::string("\xef\xbb\xbfTITLE \"Identity # test\" # comment\r\n") + kIdentity);
  Check(identity.title == "Identity # test", "Quoted titles must preserve hash characters");
  Near(prooftray::SampleLut(identity, {0, 0, 0}), {0, 0, 0});
  Near(prooftray::SampleLut(identity, {1, 1, 1}), {1, 1, 1});
  Near(prooftray::SampleLut(identity, {-0.2f, 0.5f, 1.2f}), {0, 0.5f, 1});
  auto swapped = identity;
  for (size_t index = 0; index < swapped.values.size(); index += 3) std::swap(swapped.values[index], swapped.values[index + 2]);
  auto nonlinear = identity;
  std::fill(nonlinear.values.begin(), nonlinear.values.end(), 0.0f);
  std::fill(nonlinear.values.end() - 3, nonlinear.values.end(), 1.0f);
  prooftray::Rgb point{0.2f, 0.4f, 0.7f};
  do {
    Near(prooftray::SampleLut(identity, point), point);
    Near(prooftray::SampleLut(swapped, point), {point[2], point[1], point[0]});
    // A cube with only its white corner set yields min(R,G,B) under tetrahedral interpolation.
    Near(prooftray::SampleLut(nonlinear, point), {0.2f, 0.2f, 0.2f});
  } while (std::next_permutation(point.begin(), point.end()));

  const auto curves = prooftray::ParseCube("LUT_1D_SIZE 3\nLUT_1D_INPUT_RANGE -1 1\n0 1 0\n0.25 0.5 0.75\n1 0 1\n");
  Near(prooftray::SampleLut(curves, {-0.5f, 0.0f, 0.5f}), {0.125f, 0.5f, 0.875f});
  const auto domains = prooftray::ParseCube(std::string("DOMAIN_MIN 0 -1 -2\nDOMAIN_MAX 1 1 2\n") + kIdentity);
  Check(!identity.domain_declared && domains.domain_declared && curves.domain_declared, "Lost explicit cube domain or INPUT_RANGE");
  const auto explicit_unit = prooftray::ParseCube(std::string("DOMAIN_MIN 0 0 0\nDOMAIN_MAX 1 1 1\n") + kIdentity);
  Check(explicit_unit.domain_declared && prooftray::SerializeLut(identity) == prooftray::SerializeLut(explicit_unit), "Canonical LUT ID changed");
  Near(prooftray::SampleLut(domains, {0.5f, 0, 0}), {0.5f, 0.5f, 0.5f});
  for (const auto* text : {"", "LUT_3D_SIZE 66\n", "LUT_3D_SIZE 2\n0 0 0\n", "LUT_1D_SIZE 2\nLUT_3D_SIZE 2\n",
                          "LUT_1D_SIZE 2\n0 0 0 extra\n1 1 1\n", "LUT_1D_SIZE 2\n0 0 0\nNaN 1 1\n"}) {
    Reject([&] { prooftray::ParseCube(text); });
  }
  Reject([] { prooftray::ParseCube(std::string(kIdentity) + "1 1 1\n"); });
  Reject([] { prooftray::ParseCube(std::string("DOMAIN_MIN 1 0 0\n") + kIdentity); });
  Reject([] { prooftray::ParseCube(std::string("LUT_1D_INPUT_RANGE 0 1\n") + kIdentity); });
  Reject([] { prooftray::ParseCube(std::string("LUT_3D_INPUT_RANGE 0 1\nDOMAIN_MIN 0 0 0\n") + kIdentity); });
  auto invalid = identity;
  invalid.values[0] = std::numeric_limits<float>::max();
  Reject([&] { prooftray::ValidateLut(invalid); });
  invalid = identity;
  invalid.domain_max[0] = std::numeric_limits<float>::denorm_min();
  Reject([&] { prooftray::ValidateLut(invalid); });
}

void BinaryAndHald() {
  const auto identity = prooftray::ParseCube(kIdentity);
  auto bytes = prooftray::SerializeLut(identity);
  Check(bytes.size() == 40 + 8 * 3 * 4 && bytes[8] == 3 && bytes[12] == 2, "Unexpected binary LUT header");
  const auto loaded = prooftray::ParseLutBinary(bytes);
  Check(loaded.values == identity.values && loaded.domain_min == identity.domain_min && loaded.domain_max == identity.domain_max,
        "Binary round trip changed the color table");
  auto damaged = bytes;
  damaged[0] = 'X';
  Reject([&] { prooftray::ParseLutBinary(damaged); });
  bytes.pop_back();
  Reject([&] { prooftray::ParseLutBinary(bytes); });
  damaged = prooftray::SerializeLut(identity);
  damaged[12] = 65;
  Reject([&] { prooftray::ParseLutBinary(damaged); });

  // Level 2 Hald: 8x8 pixels contain a 4x4x4 RGB cube, red fastest. Include row padding.
  std::vector<uint8_t> image(8 * 36, 42);
  for (size_t index = 0; index < 64; ++index) {
    const auto offset = index / 8 * 36 + index % 8 * 4;
    image[offset] = static_cast<uint8_t>((index % 4) * 85);
    image[offset + 1] = static_cast<uint8_t>((index / 4 % 4) * 85);
    image[offset + 2] = static_cast<uint8_t>((index / 16) * 85);
    image[offset + 3] = 255;
  }
  const auto hald = prooftray::FromHaldRgba(image, 8, 36);
  Near(prooftray::SampleLut(hald, {0.15f, 0.67f, 0.83f}), {0.15f, 0.67f, 0.83f});
  Reject([&] { prooftray::FromHaldRgba(image, 7, 36); });
  Reject([&] { prooftray::FromHaldRgba(image, 8, 31); });
  image[3] = 254;
  Reject([&] { prooftray::FromHaldRgba(image, 8, 36); });
}

void PixelAndLook() {
  const auto identity = prooftray::ParseCube(kIdentity);
  std::vector<uint8_t> image{51, 102, 179, 255, 32, 64, 96, 128, 99, 98, 97, 96};
  const auto original = image;
  prooftray::ApplyColorLookRgba(image, 2, 1, 12, true, {}, &identity);
  Check(image == original, "Identity LUT must preserve pixels, alpha and padding");
  const auto inverse = prooftray::ParseCube("LUT_1D_SIZE 2\n1 1 1\n0 0 0\n");
  prooftray::ColorLook disabled;
  disabled.strength = 0;
  prooftray::ApplyColorLookRgba(image, 2, 1, 12, true, disabled, &inverse);
  Check(image == original, "Disabling the LUT must preserve unedited pixels");
  prooftray::ColorLook gray;
  gray.saturation = 0;
  prooftray::ApplyColorLookRgba(image, 2, 1, 12, true, gray, nullptr);
  for (size_t pixel : {0, 4}) Check(image[pixel] == image[pixel + 1] && image[pixel] == image[pixel + 2], "Grayscale channels differ");
  Check(image[3] == 255 && image[7] == 128 && image[8] == 99, "Alpha or row padding was overwritten");
  prooftray::ColorLook exposure;
  exposure.exposure_ev = 1;
  Near(prooftray::ApplyColorLook({0.5f, 0.5f, 0.5f}, exposure, nullptr), {0.685836f, 0.685836f, 0.685836f});
  prooftray::ColorLook invalid;
  invalid.strength = std::numeric_limits<float>::infinity();
  Reject([&] { prooftray::ValidateLook(invalid); });
  Reject([&] { prooftray::ApplyColorLookRgba(image, 4, 1, 12, true, {}, nullptr); });
  Reject([&] { prooftray::ApplyColorLookRgba(image, 2, 2, 12, true, {}, nullptr); });
}

void PreviewHistogramSampling() {
  constexpr int side = 1024, stride = side * 4 + 8;
  std::vector<uint8_t> pixels(stride * side, 255);
  for (int y = 0; y < side; ++y) {
    for (int x = 0; x < side; ++x) {
      auto* pixel = pixels.data() + y * stride + 4 * x;
      pixel[0] = static_cast<uint8_t>(x);
      pixel[1] = static_cast<uint8_t>(y);
      pixel[2] = x % 2 ? 255 : 0;
    }
  }
  const auto direct = prooftray::PreviewHistogramRgba(pixels, side, side, stride, true, nullptr, nullptr);
  prooftray::ColorLook look;
  look.exposure_ev = .5f;
  const auto graded = prooftray::PreviewHistogramRgba(pixels, side, side, stride, true, &look, nullptr);
  std::array<bool, 256> expected{};
  for (int level = 0; level < 256; ++level) {
    const float value = level / 255.f;
    const auto rgb = prooftray::ApplyColorLook({value, value, value}, look, nullptr);
    expected[std::lround(rgb[0] * 255)] = true;
  }
  for (int level = 0; level < 256; ++level) {
    constexpr int count = side * side / 256 * 255;
    Check(direct[level] == count && direct[256 + level] == count, "Full histogram lost ramp pixels");
    Check((graded[level] > 0) == expected[level] && (graded[256 + level] > 0) == expected[level], "Graded ramp histogram has false gaps");
    Check((graded[512 + level] > 0) == (level == 0 || level == 255), "Two-tone channel acquired intermediate levels");
  }
  const std::array<uint8_t, 8> alpha{0, 0, 0, 0, 64, 32, 0, 128};
  const auto weighted = prooftray::PreviewHistogramRgba(alpha, 2, 1, 8, true, nullptr, nullptr);
  Check(weighted[128] == 128 && weighted[256 + 64] == 128 && weighted[512] == 128, "Histogram lost straight RGB or opacity");
}

void PreviewHistogramAtTwoStops() {
  const std::array<uint8_t, 8> black_white{0, 0, 0, 255, 255, 255, 255, 255};
  prooftray::ColorLook look;
  look.exposure_ev = 2;
  const auto table = prooftray::BakeColorLook(look, nullptr);
  const auto curve_input = prooftray::PreviewHistogramRgba(black_white, 2, 1, 8, true, &look, nullptr, {6, 2});
  const auto preview = prooftray::PreviewHistogramRgba(black_white, 2, 1, 8, true, nullptr, &table, {6, 2});
  const auto native_size = prooftray::PreviewHistogramRgba(black_white, 2, 1, 8, true, nullptr, &table, {2, 1});
  // Four visible columns sample 0, .25, .75 and 1. Exposure clips the last two, and the letterbox contributes no black.
  const auto mid = std::lround(prooftray::ApplyColorLook({.25f, .25f, .25f}, look, nullptr)[0] * 255);
  Check(mid > 64 && mid < 255, "Exposure fixture does not distinguish the order of filtering and grading");
  Check(curve_input == preview && preview[0] == 510 && preview[mid] == 510 && preview[255] == 1020,
        "Scaled histogram must apply +2 EV to filtered texture values");
  Check(native_size[0] == 255 && native_size[mid] == 0 && native_size[255] == 255, "Native-size histogram acquired a false midtone");
  const std::array<uint8_t, 8> transparent_red{0, 0, 0, 0, 255, 0, 0, 255};
  const auto alpha = prooftray::PreviewHistogramRgba(transparent_red, 2, 1, 8, true, nullptr, nullptr, {6, 2});
  Check(alpha[255] == 1020 && alpha[256] == 1020 && alpha[512] == 1020, "Filtered premultiplied edges tinted the histogram");
}

void DisplayEncoding() {
  const auto identity = prooftray::ParseCube(kIdentity);
  prooftray::ColorLook look;
  look.input_encoding = look.output_encoding = prooftray::LutEncoding::Rec709Gamma24;
  Near(prooftray::ApplyColorLook({0.01f, 0.35f, 0.8f}, look, &identity), {0.01f, 0.35f, 0.8f});
  const auto ramp = prooftray::ParseCube("LUT_1D_SIZE 2\nDOMAIN_MIN .1 .1 .1\nDOMAIN_MAX .9 .9 .9\n.2 .2 .2\n.7 .7 .7\n");
  const float input = 0.25f;
  const double linear = std::pow((input + .055) / 1.055, 2.4);
  const double gamma = std::pow(linear, 1.0 / 2.4);
  const double table = .2 + .5 * (gamma - .1) / .8;
  const float expected = static_cast<float>(1.055 * std::pow(std::pow(table, 2.4), 1.0 / 2.4) - .055);
  Near(prooftray::ApplyColorLook({input, input, input}, look, &ramp), {expected, expected, expected});
  look.strength = 0.5f;
  const float mixed = (expected + input) / 2;
  Near(prooftray::ApplyColorLook({input, input, input}, look, &ramp), {mixed, mixed, mixed});
  look.strength = 0;
  look.exposure_ev = 1.5f;
  const float exposed = static_cast<float>(1.055 * std::pow(linear * std::exp2(1.5), 1.0 / 2.4) - .055);
  Near(prooftray::ApplyColorLook({input, input, input}, look, &ramp), {exposed, exposed, exposed});
}

void SceneEncodings() {
  using E = prooftray::LutEncoding;
  struct Reference { E id; double black, gray, high; std::array<float, 9> matrix; };
  // Independent row-major reference matrices. DJI publishes Rec.709 -> D-Gamut directly (2017 white paper, p.5).
  // Others use inverse(published gamut -> XYZ D65) * Rec.709 -> XYZ: RED Rev B, Panasonic Rev 1, ARRI LogC3/4,
  // BT.2020/709 and the D65 Sony/Cinema Gamut data (Sony/Canon OCIO built-ins, cross-checked with Colour's tabulated XYZ matrices).
  const Reference references[] = {
      {E::Linear709, 0., .18, 4.,
       {1.0000000f, 0.f, 0.f, 0.f, 1.0000000f, 0.f, 0.f, 0.f, 1.0000000f}},
      // Khronos Data Format v1.4 S-Log2, reflection input; gray also matches OCIO's Sony CLF v1.0.
      {E::SLog2, .088251291513, .339532524634, .802566542757,
       {.5660491f, .3427631f, .0911878f, .0769614f, .7990545f, .1239841f, .0223509f, .1086121f, .8690370f}},
      {E::SLog3Cine, 95./1023., 420./1023., .749098912,
       {.6456795f, .2591145f, .0952060f, .0875300f, .7596996f, .1527704f, .0369574f, .1292809f, .8337617f}},
      {E::SLog3, 95./1023., 420./1023., .749098912,
       {.5660491f, .3427631f, .0911878f, .0769614f, .7990545f, .1239841f, .0223509f, .1086121f, .8690370f}},
      {E::NLog, .124372628, 372.032/1023., .808352057,
       {.6274039f, .3292830f, .0433131f, .0690973f, .9195404f, .0113623f, .0163914f, .0880133f, .8955952f}},
      {E::Log3G10, .091551488, 1./3., .627292998,
       {.5419464f, .3601488f, .0979060f, .0769893f, .7679682f, .1550422f, .0588724f, .2734947f, .6676333f}},
      {E::VLog, .125, .423311449, .743840586,
       {.5851967f, .3226424f, .0921624f, .0785881f, .8196271f, .1017843f, .0227941f, .1142167f, .8629890f}},
      {E::CLog2, .092864125, .398254692, .717735807,
       {.5561575f, .3291746f, .1146679f, .0824133f, .7574698f, .1601170f, .0331045f, .2258120f, .7410836f}},
      {E::CLog3, .12512219, .343389370, .794468704,
       {.5561575f, .3291746f, .1146679f, .0824133f, .7574698f, .1601170f, .0331045f, .2258120f, .7410836f}},
      {E::DLog, .0929, .398764556, .738174962,
       {.6163000f, .2857000f, .0980000f, .0505000f, .7990000f, .1505000f, .0292000f, .1604000f, .8104000f}},
      {E::LogC3, .092809, .391006832, .718701631,
       {.6313206f, .2708005f, .0978788f, .0368199f, .7930370f, .1701430f, .0173699f, .1487891f, .8338409f}},
      {E::LogC4, 95./1023., .278395837, .555135049,
       {.5658365f, .3403310f, .0938325f, .0886256f, .8093468f, .1020275f, .0177500f, .1094476f, .8728023f}},
  };
  const auto lut = prooftray::ParseCube(kIdentity);
  const std::array<int, 12> wire_codes{4, 14, 5, 6, 3, 7, 8, 9, 10, 11, 12, 13};
  for (size_t index = 0; index < wire_codes.size(); ++index) {
    const auto& ref = references[index];
    Check(static_cast<int>(ref.id) == wire_codes[index], "LUT encoding JNI code changed");
    const auto& spec = prooftray::GetLogEncoding(ref.id);
    const double black = spec.encode(0.), gray = spec.encode(.18), high = spec.encode(4.);
    if (!(std::isfinite(black) && std::isfinite(gray) && std::isfinite(high) && black < gray && gray < high &&
          std::abs(black - ref.black) < 1e-6 && std::abs(gray - ref.gray) < 1e-6 && std::abs(high - ref.high) < 1e-6)) {
      throw std::runtime_error(std::string(spec.name) + ": black / gray / highlight code mismatch");
    }
    for (int c = 0; c < 3; ++c) {
      prooftray::Rgb primary{};
      primary[c] = 1;
      const auto actual = spec.ToGamut(primary);
      Near(actual, {ref.matrix[c], ref.matrix[3+c], ref.matrix[6+c]}, 1e-4f);
    }
    Near(spec.ToGamut({1,1,1}), {1,1,1});
    Check(std::isfinite(spec.encode(-.05)) && spec.encode(-.05) <= black, "Invalid negative extension");
    Check(ref.id == E::NLog || spec.encode(-.05) < black, "Negative linear input was clipped");
    prooftray::ColorLook look;
    look.input_encoding = ref.id;
    look.output_encoding = E::Rec709Gamma24;
    for (const auto range : {prooftray::LogRange::Full10, prooftray::LogRange::Video10}) {
      const auto grade = prooftray::PrepareLogGrade(range, 1.f, look);
      const double coord = range == prooftray::LogRange::Full10 ? ref.gray : (ref.gray * 1023. - 64.) / 876.;
      const double light = std::pow(coord, 2.4);
      const float expected = static_cast<float>(light <= .0031308 ? 12.92 * light : 1.055 * std::pow(light, 1. / 2.4) - .055);
      Near(prooftray::RenderLogPixel({.18f, .18f, .18f}, lut, grade), {expected, expected, expected});
    }
  }
  Reject([] { prooftray::GetLogEncoding(static_cast<E>(1)); });
  Reject([] { prooftray::GetLogEncoding(E::Direct); });
}

void RawExposureReference() {
  using E = prooftray::LutEncoding;
  using R = prooftray::LogRange;
  const auto& reference = prooftray::kGenericRawExposureReference;
  Check(!reference.calibrated && std::abs(reference.scale * .081f - .18f) < 1e-6f, "Generic reference is not a camera calibration");
  const auto lut = prooftray::ParseCube(kIdentity);
  const prooftray::Rgb camera{.03f, .04f, .05f};
  for (const auto encoding : {E::NLog, E::SLog3Cine, E::Linear709}) {
    for (const auto range : {R::Full10, R::Video10}) {
      prooftray::ColorLook look;
      look.input_encoding = encoding;
      look.output_encoding = E::Rec709Gamma24;
      prooftray::Rgb scene = camera;
      for (float& c : scene) c *= reference.scale;
      const auto expected = prooftray::RenderLogPixel(scene, lut, prooftray::PrepareLogGrade(range, 1.f, look));
      Near(prooftray::RenderLogPixel(camera, lut, prooftray::PrepareRawLogGrade(range, 0.f, look)), expected);

      // User K and exposure stay independent of the reference; both +1 EV adjustments must count exactly once.
      auto exposed = scene;
      for (float& c : exposed) c *= 4;
      const auto brighter = prooftray::RenderLogPixel(exposed, lut, prooftray::PrepareLogGrade(range, 1.f, look));
      look.exposure_ev = 1;
      Near(prooftray::RenderLogPixel(camera, lut, prooftray::PrepareRawLogGrade(range, 1.f, look)), brighter);

      look.strength = 0;
      auto exposed_camera = camera;
      for (float& c : exposed_camera) c *= 2;
      const auto display = prooftray::RenderRawDefault(exposed_camera, prooftray::PrepareRawDefault());
      const auto base = prooftray::RenderLogPixel(camera, lut, prooftray::PrepareRawLogGrade(range, 1.f, look));
      Near(base, display);
      Near(prooftray::RenderLogPixel(camera, lut, prooftray::PrepareRawLogGrade(range, -2.f, look)), display);
      look.strength = .5f;
      prooftray::Rgb half{};
      for (int c = 0; c < 3; ++c) half[c] = (display[c] + brighter[c]) / 2;
      Near(prooftray::RenderLogPixel(camera, lut, prooftray::PrepareRawLogGrade(range, 1.f, look)), half);
    }
  }
  prooftray::ColorLook look;
  look.input_encoding = E::NLog;
  look.output_encoding = E::Rec709Gamma24;
  look.strength = 0;
  const prooftray::Rgb raw_gray{.081f, .081f, .081f};
  const auto neutral = prooftray::RenderLogPixel(raw_gray, lut, prooftray::PrepareRawLogGrade(R::Full10, 0.f, look));
  Near(neutral, prooftray::RenderRawDefault(raw_gray, prooftray::PrepareRawDefault()));
  Near(prooftray::RenderLogPixel(raw_gray, lut, prooftray::PrepareRawLogGrade(R::Full10, 0.f, look, {1.f, "unity", true})),
       neutral);  // A scene reference change cannot change the ordinary display endpoint.
  Reject([&] { prooftray::PrepareRawLogGrade(R::Full10, 4.f, look); });
  Reject([&] { prooftray::PrepareRawLogGrade(R::Full10, 0.f, look, {0.f, "invalid", false}); });
  Reject([&] { prooftray::PrepareRawLogGrade(R::Full10, 0.f, look, {std::numeric_limits<float>::infinity(), "invalid", false}); });
}

void SceneLutDomain() {
  using E = prooftray::LutEncoding;
  const auto lut = prooftray::ParseCube("LUT_1D_SIZE 2\nDOMAIN_MIN -1 -1 -1\nDOMAIN_MAX 4 4 4\n0 0 0\n1 1 1\n");
  prooftray::ColorLook look;
  look.input_encoding = E::Linear709;
  look.output_encoding = E::Rec709Gamma24;
  const prooftray::Rgb scene{-.5f, .18f, 3.f};
  for (const auto range : {prooftray::LogRange::Full10, prooftray::LogRange::Video10}) {
    const auto grade = prooftray::PrepareLogGrade(range, 1.f, look);
    const auto fitted = prooftray::RenderLogPixel(scene, lut, grade);
    prooftray::Rgb expected{};
    for (int i = 0; i < 3; ++i) {
      const double input = range == prooftray::LogRange::Full10 ? scene[i] : (scene[i]*1023. - 64.) / 876.;
      const double display = std::pow((input + 1.) / 5., 2.4);
      expected[i] = float(display <= .0031308 ? 12.92*display : 1.055*std::pow(display, 1./2.4) - .055);
    }
    Near(fitted, expected);
  }
  Reject([&] { prooftray::ValidateLook(look); });  // Never accept scene encoding on the 8-bit RGB path.
}

void DirectLogExport() {
  using E = prooftray::LutEncoding;
  using R = prooftray::LogRange;
  prooftray::ColorLook look;
  look.input_encoding = E::SLog3Cine;
  look.output_encoding = E::Direct;
  look.strength = 0.f;
  look.contrast = 1.f;
  look.saturation = 0.f;
  const prooftray::Rgb raw_gray{.081f, .081f, .081f};
  const auto grade = prooftray::PrepareRawLogGrade(R::Video10, 0.f, look);
  const float gray = 420.f / 1023.f;
  Near(prooftray::EncodeLogPixel(raw_gray, grade), {gray, gray, gray});
  // Export is full-range even when the last LUT used video-range coordinates or zero strength.
  const float video = (420.f - 64.f) / 876.f;
  Near(prooftray::EncodeLogPixel(raw_gray, grade, R::Video10), {video, video, video});
  look.exposure_ev = 1.f;
  const auto brighter_grade = prooftray::PrepareRawLogGrade(R::Full10, 1.f, look);
  const float brighter = static_cast<float>((420. + 261.5 * std::log10((.72 + .01) / .19)) / 1023.);
  Near(prooftray::EncodeLogPixel(raw_gray, brighter_grade), {brighter, brighter, brighter});
}


}  // namespace

int main() {
  try {
    CubeAndInterpolation();
    BinaryAndHald();
    PixelAndLook();
    PreviewHistogramSampling();
    PreviewHistogramAtTwoStops();
    DisplayEncoding();
    SceneEncodings();
    RawExposureReference();
    SceneLutDomain();
    DirectLogExport();
    Check(std::abs(prooftray::NLogCode(.18) - 372.032) < .001, "N-Log metered gray changed");
    Check(prooftray::NLogCode(1) == 619 && prooftray::NLogCode(4) > 826, "Reference white or highlight headroom lost");
    Near(prooftray::GetLogEncoding(prooftray::LutEncoding::NLog).ToGamut({1, 1, 1}), {1, 1, 1});
    Near(prooftray::GetLogEncoding(prooftray::LutEncoding::NLog).ToGamut({2, 0, 0}), {1.2548078f, .1381946f, .0327828f});
    std::cout << "Color LUT tests passed\n";
    return 0;
  } catch (const std::exception& error) { std::cerr << error.what() << '\n'; return 1; }
}
