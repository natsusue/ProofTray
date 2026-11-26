# ProofTray core

`include/prooftray/catalog.h` is the platform-independent C++20 catalog interface.
`QueryCatalog` performs filtering, sorting, RAW/JPEG pairing and optional burst folding. Input order is not a sorting tie-breaker: asset IDs are.

Filter semantics:

- Different fields combine with AND. Format / pick / download selections combine with OR. Required tags combine with AND.
- Numeric and capture-time range bounds are inclusive. Unspecified bounds are open.
- A missing selected metadata field is pending. A known mismatch in another field excludes the file even when some metadata is missing.
- Pending count includes pending rows hidden by `include_unknown_metadata=false`; matched count only includes definite matches.
- Free-text search covers filenames and tags only. Missing camera/lens EXIF does not make a filename mismatch pending.
- Tags are local user metadata. An empty tag collection is known empty, not missing EXIF.
- Unknown capture times and file sizes sort after known values in either chronological direction.

Grouping:

- RAW/JPEG pairing checks the full catalog before filtering. A RAW-only query can therefore match paired RAWs without displaying their JPEG partners.
- Pair keys require source, folder, camera, filename stem and exact capture timestamp. Ambiguous duplicate pairs remain unpaired.
- Disabling burst folding keeps verified RAW/JPEG pairs together.
- Explicit burst IDs require a matching source/folder/camera context and known capture timestamps.
- Heuristic bursts link adjacent distinct captures at most 1 second apart, in the same source/folder/camera/lens context.
- A heuristic is labeled `inferred_burst`; it is not proof of camera burst mode. Explicit groups are never joined to heuristic groups.
- Missing identity metadata prevents grouping, so local documents with unavailable EXIF can remain singletons.
- Group anchors use the lexicographically lowest member asset ID and remain stable when a filter hides that anchor.

Android adapter:

- `CatalogEngine.kt` lowercases searchable Unicode text using `Locale.ROOT`, batches metadata and invokes one native call.
- `catalog_jni.cc` validates array dimensions, enum values, tag ranges and nulls. It converts UTF-16 to UTF-8 and releases JNI references promptly.
- Wire integers use `Long.MIN_VALUE` for missing values; wire decimals use NaN. No image pixels or large image buffers cross this API.
- Returned integer-array version is 1. Header: version, matched count, pending count, group count.
- Each group contains anchor input index, inferred flag, member count, and member input indexes in display order.
- Keep the native method name if code shrinking is enabled in a future release.

`tests/catalog_test.cc` contains regression cases for missing metadata, range boundaries, combined filters, conservative grouping and invalid inputs.
Desktop tests are enabled by default through `PROOFTRAY_BUILD_TESTS`. This directory can be opened independently in CLion.
From the repository root, a standalone desktop checkout can use:

```sh
cmake -S core -B out/core
cmake --build out/core --config Debug
ctest --test-dir out/core -C Debug --output-on-failure
```

The optional presets are `core-debug` for this directory and `host-debug` for the parent project.
Configure the parent with `PROOFTRAY_BUILD_TIFF_TESTS=ON` to include Log TIFF readback tests. This requires zlib and the libtiff submodule.
The desktop target names and CMake options retain their published names for existing toolchains and CLion run configurations.

The optional `prooftray_heif_core` target exposes the C++20 still-image decoder in `prooftray/heif_image.h`.
Android and iOS configurations enable it by default. Other platforms can enable `PROOFTRAY_BUILD_HEIF`.
The caller supplies the encoded bytes and a destination buffer for premultiplied sRGB RGBA8. Browsers may use embedded thumbnails.
Editing and export decode the primary image.

Color processing:

- `color_lut.h` / `color_lut.cc` parse CUBE and decoded Hald RGBA8 data without Android dependencies.
- 1D curves interpolate independently; 3D tables use red-fastest tetrahedral interpolation.
- Exposure, warmth and tint act in linear light. LUT strength blends the LUT result before display adjustments.
- Display adjustments apply contrast, tonal controls, master and channel curves, HSL, color grading, vibrance and saturation in SDR sRGB.
- RGBA8 processing preserves alpha and row padding. Premultiplied alpha is removed before grading and restored afterwards.
- The versioned little-endian LUT binary stores dimensions, domain and floats; titles and attribution live in Android metadata.
- `tests/color_lut_test.cc` adds malformed input, interpolation, binary round-trip, Hald ordering and alpha/stride regressions.
- `include/prooftray/linear_raw.h` shares the LibRaw raster adapter between Android JNI and `tools/nlog_probe.cc`.
  Configure the parent with `PROOFTRAY_BUILD_NLOG_PROBE=ON` to build the optional desktop probe.

Location protocol:

- `nikon_location.h` / `nikon_location.cc` encode the 41-byte camera location record independently of Android.
- Coordinates use degrees and fractional minutes; timestamps are UTC, with explicit presence and validity bits.
- Optional altitude/satellite fields are omitted when unavailable. Invalid samples cannot produce a valid GPS record.
- PTP command construction now supports the host-to-camera data direction used by location writes.
- `tests/nikon_location_test.cc` covers the wire representation and boundaries.

## Ubuntu build

Install the C++ toolchain and zlib headers, then run the script from the repository root.

```sh
sudo apt install build-essential cmake git ninja-build zlib1g-dev
bash core/build-ubuntu.sh
```

The script builds the desktop C++ core and the test executables, including Log TIFF, under `out/build/ubuntu-core`.
Run the tests separately with `ctest --test-dir out/build/ubuntu-core --output-on-failure`.
