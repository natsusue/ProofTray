#include "prooftray/log_tiff.h"

#include <tiffio.h>

#include <algorithm>
#include <cmath>
#include <cstdarg>
#include <cstdint>
#include <cstdio>
#include <fcntl.h>
#include <locale>
#include <memory>
#include <sstream>
#include <stdexcept>
#include <string>
#include <vector>

#include "prooftray/raw_exposure.h"

namespace prooftray {
namespace {

struct TiffError {
  char message[512]{};
  static int Handle(TIFF*, void* data, const char*, const char* format, va_list args) {
    std::vsnprintf(static_cast<TiffError*>(data)->message, 512, format, args);
    return 1;
  }
  static int Ignore(TIFF*, void*, const char*, const char*, va_list) { return 1; }
  void Check(bool ok) const {
    if (!ok) throw std::runtime_error(message[0] ? message : "Cannot write Log TIFF");
  }
};

void ReleaseTiff(TIFF* tiff) {
  // Success is explicitly flushed below. Cleanup must not finalize an interrupted file or close the borrowed fd.
  TIFFSetMode(tiff, O_RDONLY);
  TIFFCleanup(tiff);
}

std::string Description(const LogExportSettings& settings, const LogGrade& grade) {
  std::ostringstream out;
  out.imbue(std::locale::classic());
  out.precision(9);
  out << "{\"ProofTrayLog\":1,\"encoding\":\"" << grade.spec.name
      << "\",\"encodingId\":" << static_cast<int>(settings.encoding)
      << ",\"range\":\"full\",\"sampleFormat\":\"uint16\",\"normalization\":\"code10/1023\""
      << ",\"exposureEv\":" << settings.exposure_ev << ",\"warmth\":" << settings.warmth << ",\"gainEv\":" << settings.gain_ev
      << ",\"tint\":" << settings.tint
      << ",\"rawReference\":\"" << kGenericRawExposureReference.source << "\",\"referenceScale\":" << kGenericRawExposureReference.scale
      << ",\"referenceCalibrated\":false,\"whiteBalance\":\"as-shot\",\"displayCurve\":false,\"lut\":false"
      << ",\"lensCorrection\":false,\"sensorClipping\":\"LibRaw highlight=0\"}";
  return out.str();
}

}  // namespace

void WriteLogTiff(int fd, int width, int height, const LogExportSettings& settings,
                  const std::function<Rgb(int, int)>& read, const std::function<bool()>& cancelled) {
  if (fd < 0 || width <= 0 || height <= 0 || width > 100000 || height > 100000 || int64_t{width} * height > 100000000 || !read || !cancelled ||
      settings.encoding == LutEncoding::Linear709) throw std::invalid_argument("Invalid Log TIFF parameters");
  ColorLook look;
  look.input_encoding = settings.encoding;
  look.exposure_ev = settings.exposure_ev;
  look.warmth = settings.warmth;
  look.tint = settings.tint;
  const auto grade = PrepareRawLogGrade(LogRange::Full10, settings.gain_ev, look);
  const auto description = Description(settings, grade);
  const auto check_cancelled = [&] { if (cancelled()) throw std::runtime_error("Log TIFF export cancelled"); };
  check_cancelled();
  TiffError error;
  std::unique_ptr<TIFFOpenOptions, decltype(&TIFFOpenOptionsFree)> options(TIFFOpenOptionsAlloc(), TIFFOpenOptionsFree);
  if (!options) throw std::bad_alloc();
  TIFFOpenOptionsSetMaxSingleMemAlloc(options.get(), 8 * 1024 * 1024);
  TIFFOpenOptionsSetErrorHandlerExtR(options.get(), TiffError::Handle, &error);
  TIFFOpenOptionsSetWarningHandlerExtR(options.get(), TiffError::Ignore, nullptr);
  std::unique_ptr<TIFF, decltype(&ReleaseTiff)> tiff(TIFFFdOpenExt(fd, "ProofTray Log", "wl", options.get()), ReleaseTiff);
  error.Check(tiff != nullptr);
  const auto set = [&](uint32_t tag, auto... values) { error.Check(TIFFSetField(tiff.get(), tag, values...) == 1); };
  set(TIFFTAG_IMAGEWIDTH, static_cast<uint32_t>(width));
  set(TIFFTAG_IMAGELENGTH, static_cast<uint32_t>(height));
  set(TIFFTAG_SAMPLESPERPIXEL, 3);
  set(TIFFTAG_BITSPERSAMPLE, 16);
  set(TIFFTAG_SAMPLEFORMAT, SAMPLEFORMAT_UINT);
  set(TIFFTAG_PHOTOMETRIC, PHOTOMETRIC_RGB);
  set(TIFFTAG_PLANARCONFIG, PLANARCONFIG_CONTIG);
  set(TIFFTAG_ORIENTATION, ORIENTATION_TOPLEFT);
  set(TIFFTAG_COMPRESSION, COMPRESSION_ADOBE_DEFLATE);
  set(TIFFTAG_PREDICTOR, PREDICTOR_HORIZONTAL);
  set(TIFFTAG_ZIPQUALITY, 6);
  set(TIFFTAG_ROWSPERSTRIP, TIFFDefaultStripSize(tiff.get(), 0));
  set(TIFFTAG_SOFTWARE, "ProofTray Log exporter");
  set(TIFFTAG_IMAGEDESCRIPTION, description.c_str());
  // Log code values are deliberately not labeled with an sRGB ICC profile, transfer function or EXIF ColorSpace=1.
  std::vector<uint16_t> row(static_cast<size_t>(width) * 3);
  for (int y = 0; y < height; ++y) {
    check_cancelled();
    for (int x = 0; x < width; ++x) {
      const auto code = EncodeLogPixel(read(x, y), grade);
      for (int c = 0; c < 3; ++c) {
        if (!std::isfinite(code[c])) throw std::runtime_error("Non-finite Log output");
        row[size_t(x) * 3 + c] = static_cast<uint16_t>(std::lround(std::clamp(code[c], 0.f, 1.f) * 65535.f));
      }
    }
    error.Check(TIFFWriteScanline(tiff.get(), row.data(), static_cast<uint32_t>(y), 0) == 1);
  }
  check_cancelled();
  error.Check(TIFFWriteDirectory(tiff.get()) == 1);
  error.Check(TIFFFlush(tiff.get()) == 1);
}

}  // namespace prooftray
