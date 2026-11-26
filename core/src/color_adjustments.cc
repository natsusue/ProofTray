#include "prooftray/color_lut.h"

#include <algorithm>
#include <cmath>
#include <mutex>
#include <stdexcept>

namespace prooftray {
namespace {

float Unit(float x) { return std::clamp(x, 0.f, 1.f); }
float Smooth(float a, float b, float x) { const float t = Unit((x - a) / (b - a)); return t * t * (3 - 2 * t); }
float Luma(const Rgb& c) { return .2126f * c[0] + .7152f * c[1] + .0722f * c[2]; }
void Range(float value, float low, float high) {
  if (!std::isfinite(value) || value < low || value > high) throw std::invalid_argument("Invalid advanced color parameter");
}

// Cylindrical HSL over encoded sRGB, as defined by CSS Color 4. Hue is in degrees.
Rgb ToHsl(const Rgb& c) {
  const float high = std::max({c[0], c[1], c[2]}), low = std::min({c[0], c[1], c[2]});
  const float delta = high - low, light = (high + low) * .5f;
  if (delta <= 1e-7f) return {0, 0, light};
  float hue = high == c[0] ? (c[1] - c[2]) / delta : high == c[1] ? 2 + (c[2] - c[0]) / delta : 4 + (c[0] - c[1]) / delta;
  return {std::fmod(hue * 60 + 360, 360.f), delta / std::max(1e-7f, 1 - std::abs(2 * light - 1)), light};
}

Rgb FromHsl(const Rgb& hsl) {
  const float h = std::fmod(hsl[0] + 720, 360.f) / 60, s = Unit(hsl[1]), l = Unit(hsl[2]);
  const float chroma = (1 - std::abs(2 * l - 1)) * s;
  const float x = chroma * (1 - std::abs(std::fmod(h, 2.f) - 1));
  Rgb result = h < 1 ? Rgb{chroma, x, 0} : h < 2 ? Rgb{x, chroma, 0} : h < 3 ? Rgb{0, chroma, x} :
      h < 4 ? Rgb{0, x, chroma} : h < 5 ? Rgb{x, 0, chroma} : Rgb{chroma, 0, x};
  for (float& c : result) c += l - chroma * .5f;
  return result;
}

Rgb MapLuma(Rgb rgb, float target) {
  const float source = Luma(rgb);
  target = Unit(target);
  for (float& c : rgb) c = target > source ? c + (1 - c) * (target - source) / std::max(1e-7f, 1 - source)
                                         : c * target / std::max(1e-7f, source);
  return rgb;
}

float Tangent(const ToneCurve& curve, size_t i) {
  if (i == 0) return (curve[1].y - curve[0].y) / (curve[1].x - curve[0].x);
  if (i + 1 == curve.size()) return (curve[i].y - curve[i - 1].y) / (curve[i].x - curve[i - 1].x);
  const float left = curve[i].x - curve[i - 1].x, right = curve[i + 1].x - curve[i].x;
  const float a = (curve[i].y - curve[i - 1].y) / left, b = (curve[i + 1].y - curve[i].y) / right;
  if (a * b <= 0) return 0;
  const float wa = 2 * right + left, wb = right + 2 * left;
  return (wa + wb) / (wa / a + wb / b);
}

template <typename Transform> ColorLut Bake(Transform transform) {
  ColorLut result;
  result.size = 65;
  result.values.reserve(size_t(result.size) * result.size * result.size * 3);
  for (int b = 0; b < result.size; ++b)
    for (int g = 0; g < result.size; ++g)
      for (int r = 0; r < result.size; ++r) {
        const auto color = transform(Rgb{r / 64.f, g / 64.f, b / 64.f});
        result.values.insert(result.values.end(), color.begin(), color.end());
      }
  return result;
}
}  // namespace

bool ColorAdjustments::IsIdentity() const {
  if (highlights || shadows || whites || blacks || vibrance) return false;
  for (const auto& band : hsl) for (float value : band) if (value) return false;
  for (const auto& wheel : grading) if (wheel[1] || wheel[2]) return false;
  for (const auto& curve : curves) for (const auto& point : curve) if (point.x != point.y) return false;
  return true;
}

void ValidateColorAdjustments(const ColorAdjustments& e) {
  for (float v : {e.highlights, e.shadows, e.whites, e.blacks, e.vibrance, e.balance}) Range(v, -1, 1);
  Range(e.blending, 0, 1);
  for (const auto& band : e.hsl) for (float v : band) Range(v, -1, 1);
  for (const auto& wheel : e.grading) { Range(wheel[0], 0, 360); Range(wheel[1], 0, 1); Range(wheel[2], -1, 1); }
  for (const auto& curve : e.curves) {
    if (curve.size() < 2 || curve.size() > 16 || curve.front().x != 0 || curve.back().x != 1) {
      throw std::invalid_argument("Invalid curve endpoints or size");
    }
    for (size_t i = 0; i < curve.size(); ++i) {
      Range(curve[i].x, 0, 1); Range(curve[i].y, 0, 1);
      if (i && curve[i].x - curve[i - 1].x < .001f) throw std::invalid_argument("Curve points overlap");
    }
  }
}

ColorAdjustments ParseColorAdjustments(std::span<const float> values) {
  if (values.size() < 63 || values.size() > 175) throw std::invalid_argument("Invalid advanced color payload");
  ColorAdjustments e;
  e.highlights = values[0]; e.shadows = values[1]; e.whites = values[2]; e.blacks = values[3]; e.vibrance = values[4];
  e.blending = values[5]; e.balance = values[6];
  size_t offset = 7;
  for (auto& band : e.hsl) for (float& v : band) v = values[offset++];
  for (auto& wheel : e.grading) for (float& v : wheel) v = values[offset++];
  for (auto& curve : e.curves) {
    if (offset == values.size()) throw std::invalid_argument("Missing curve");
    const float count = values[offset++];
    Range(count, 2, 16);
    if (count != std::floor(count) || offset + size_t(count) * 2 > values.size()) throw std::invalid_argument("Invalid curve payload");
    curve.clear();
    for (size_t i = 0; i < size_t(count); ++i) { curve.push_back({values[offset], values[offset + 1]}); offset += 2; }
  }
  if (offset != values.size()) throw std::invalid_argument("Trailing color parameters");
  ValidateColorAdjustments(e);
  return e;
}

float EvaluateToneCurve(const ToneCurve& curve, float x) {
  x = Unit(x);
  const auto upper = std::upper_bound(curve.begin(), curve.end(), x, [](float value, const CurvePoint& p) { return value < p.x; });
  const size_t i = std::clamp(size_t(upper - curve.begin()), size_t{1}, curve.size() - 1) - 1;
  const auto a = curve[i], b = curve[i + 1];
  const float h = b.x - a.x, t = (x - a.x) / h, t2 = t * t, t3 = t2 * t;
  return std::clamp((2 * t3 - 3 * t2 + 1) * a.y + (t3 - 2 * t2 + t) * h * Tangent(curve, i) +
      (-2 * t3 + 3 * t2) * b.y + (t3 - t2) * h * Tangent(curve, i + 1), std::min(a.y, b.y), std::max(a.y, b.y));
}

std::array<float, 3> ColorGradingWeights(float display_luma, float blending, float balance) {
  Range(display_luma, 0, 1); Range(blending, 0, 1); Range(balance, -1, 1);
  const float position = Unit(display_luma + balance * .25f);
  // Separate boundaries for shadows/midtones and midtones/highlights. Default blending keeps each anchor distinct;
  // larger blending widens both transitions without increasing the total strength of three equal wheel settings.
  const float half_width = .04f + .26f * blending * blending;
  const float shadows = 1 - Smooth(1.f / 3 - half_width, 1.f / 3 + half_width, position);
  const float highlights = Smooth(2.f / 3 - half_width, 2.f / 3 + half_width, position);
  const float midtones = std::max(0.f, 1 - shadows - highlights);
  const float total = shadows + midtones + highlights;
  return {shadows / total, midtones / total, highlights / total};
}

Rgb ApplyDisplayAdjustments(const Rgb& input, const ColorLook& look) {
  const auto& e = look.adjustments;
  if (e.IsIdentity() && look.contrast == 0 && look.saturation == 1) return input;
  Rgb rgb = input;
  for (float& c : rgb) {
    c = Unit(c);
    if (look.contrast != 0) {
      const float a = std::pow(c, std::exp2(look.contrast)), b = std::pow(1 - c, std::exp2(look.contrast));
      c = a / (a + b);
    }
  }
  const float luma = Luma(rgb);
  const float ev = e.shadows * (1 - Smooth(.05f, .65f, luma)) + e.highlights * Smooth(.35f, .95f, luma);
  const float gain = std::exp2(ev);
  float target = luma * gain / (1 + luma * (gain - 1));
  target += .2f * (e.whites * std::pow(luma, 3) + e.blacks * std::pow(1 - luma, 3));
  rgb = MapLuma(rgb, target);
  for (int c = 0; c < 3; ++c) rgb[c] = EvaluateToneCurve(e.curves[c + 1], EvaluateToneCurve(e.curves[0], rgb[c]));
  auto hsl = ToHsl(rgb);
  constexpr float centers[]{0, 30, 60, 120, 180, 240, 270, 300};
  int lower = 0;
  for (int i = 1; i < 8; ++i) if (hsl[0] >= centers[i]) lower = i;
  const int upper = (lower + 1) % 8;
  const float end = upper ? centers[upper] : 360.f;
  const float mix = Smooth(centers[lower], end, hsl[0]);
  if (hsl[1] > 1e-5f) {
    const float influence = Smooth(0.f, .15f, hsl[1]);
    hsl[0] += 30.f * std::lerp(e.hsl[lower][0], e.hsl[upper][0], mix) * influence;
    hsl[1] = Unit(hsl[1] * (1 + std::lerp(e.hsl[lower][1], e.hsl[upper][1], mix)));
    hsl[2] = Unit(hsl[2] + .4f * std::lerp(e.hsl[lower][2], e.hsl[upper][2], mix) * influence * 4 * hsl[2] * (1 - hsl[2]));
  }
  rgb = FromHsl(hsl);
  const float luminance = Unit(Luma(rgb));
  const auto weights = ColorGradingWeights(luminance, e.blending, e.balance);
  const float envelope = 4 * luminance * (1 - luminance);
  Rgb shift{};
  float light_shift = 0;
  for (int i = 0; i < 4; ++i) {
    const auto& wheel = e.grading[i];
    const auto color = FromHsl({wheel[0], 1, .5f});
    const float weight = i == 3 ? 1.f : weights[i];
    const float y = Luma(color), amount = .35f * wheel[1] * weight * envelope;
    for (int c = 0; c < 3; ++c) shift[c] += (color[c] - y) * amount;
    light_shift += .2f * wheel[2] * weight * envelope;
  }
  for (int c = 0; c < 3; ++c) rgb[c] = Unit(rgb[c] + shift[c]);
  rgb = MapLuma(rgb, Luma(rgb) + light_shift);
  const float high_channel = std::max({rgb[0], rgb[1], rgb[2]}), low_channel = std::min({rgb[0], rgb[1], rgb[2]});
  const float chroma = high_channel - low_channel;
  const float saturation = look.saturation * (1 + e.vibrance * (1 - chroma));
  const float y = Luma(rgb);
  for (float& c : rgb) c = Unit(y + (c - y) * saturation);
  return rgb;
}

std::shared_ptr<const ColorLut> DisplayAdjustmentsTable(const ColorLook& look) {
  if (look.adjustments.IsIdentity() && look.contrast == 0 && look.saturation == 1) return {};
  struct Entry { ColorAdjustments edits; float contrast, saturation; std::shared_ptr<const ColorLut> table; };
  static std::mutex mutex;
  static std::vector<Entry> cache;
  const auto find = [&]() -> std::shared_ptr<const ColorLut> {
    for (const auto& e : cache) if (e.edits == look.adjustments && e.contrast == look.contrast && e.saturation == look.saturation) return e.table;
    return {};
  };
  { std::lock_guard lock(mutex); if (auto table = find()) return table; }
  auto table = std::make_shared<const ColorLut>(Bake([&](const Rgb& rgb) { return ApplyDisplayAdjustments(rgb, look); }));
  std::lock_guard lock(mutex);
  if (auto existing = find()) return existing;
  if (cache.size() == 2) cache.erase(cache.begin());
  cache.push_back({look.adjustments, look.contrast, look.saturation, table});
  return table;
}

ColorLut BakeColorLook(const ColorLook& look, const ColorLut* lut) {
  ValidateLook(look);
  if (lut) ValidateLut(*lut);
  return Bake([&](const Rgb& rgb) { return ApplyColorLook(rgb, look, lut); });
}

}  // namespace prooftray
