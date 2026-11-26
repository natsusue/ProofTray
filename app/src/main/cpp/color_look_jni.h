#ifndef PROOFTRAY_COLOR_LOOK_JNI_H_
#define PROOFTRAY_COLOR_LOOK_JNI_H_

#include "jni_support.h"
#include "prooftray/color_lut.h"

namespace prooftray::jni {

inline ColorLook ReadColorLook(JNIEnv* env, jfloatArray settings, jfloatArray edits, jint input, jint output) {
  if (!settings || env->GetArrayLength(settings) != 7 || !edits) throw std::invalid_argument("Invalid color recipe");
  std::array<float, 7> values{};
  env->GetFloatArrayRegion(settings, 0, 7, values.data());
  CheckJni(env);
  if (values[5] != 2) throw std::invalid_argument("Unsupported color recipe version");
  const auto count = env->GetArrayLength(edits);
  if (count < 63 || count > 175) throw std::invalid_argument("Invalid advanced color payload");
  std::vector<float> packed(count);
  env->GetFloatArrayRegion(edits, 0, count, packed.data());
  CheckJni(env);
  ColorLook look{values[0], values[1], values[2], values[3], values[4], static_cast<LutEncoding>(input), static_cast<LutEncoding>(output),
                 values[6], ParseColorAdjustments(packed)};
  ValidateLookParameters(look);
  return look;
}

}  // namespace prooftray::jni
#endif  // PROOFTRAY_COLOR_LOOK_JNI_H_
